package com.rtmpstreamer.livestream

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.Camera
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.view.TextureView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraOpenException
import com.pedro.rtmp.flv.video.ProfileIop
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera1
import kotlin.math.roundToInt

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class VideoLiveStreamView(private val themedReactContext: ThemedReactContext) :
    FrameLayout(themedReactContext), ConnectCheckerRtmp, LifecycleEventListener {

  private val previewView = TextureView(themedReactContext)
  private val rtmpCamera = RtmpCamera1(previewView, this)
  private val connectivityManager =
      themedReactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val scaleGestureDetector =
      ScaleGestureDetector(
          themedReactContext,
          object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
              if (!enablePinchedZoom) {
                return false
              }
              val currentZoom = rtmpCamera.zoom.roundToInt()
              val delta = if (detector.scaleFactor >= 1f) 1 else -1
              applyZoomLevel(currentZoom + delta)
              return true
            }
          })
  private val networkCallback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          post { handleNetworkAvailabilityChanged(true) }
        }

        override fun onLost(network: Network) {
          post { handleNetworkAvailabilityChanged(hasActiveNetwork()) }
        }

        override fun onUnavailable() {
          post { handleNetworkAvailabilityChanged(false) }
        }
      }

  private var desiredCamera = CameraMode.BACK
  private var videoConfig = VideoConfig()
  private var audioConfig = AudioConfig()
  private var isMuted = false
  private var isVideoEnabled = true
  private var enablePinchedZoom = false
  private var previewStarted = false
  private var cleanedUp = false
  private var pendingZoomRatio = 1.0
  private var hasConnectedStream = false
  private var currentStreamUrl: String? = null
  private var pendingRetryReason: String? = null
  private var shouldReconnectStream = false
  private var isStoppingStream = false
  private var isHostPaused = false
  private var shouldResumeStreamOnHostResume = false
  private var isNetworkAvailable = hasActiveNetwork()
  private var retryAttempt = 0
  private var isSwitchingCamera = false
  private var hasPendingCameraSync = false

  init {
    rtmpCamera.setProfileIop(ProfileIop.BASELINE)
    rtmpCamera.setWriteChunkSize(RTMP_CHUNK_SIZE)
    rtmpCamera.setReTries(MAX_RETRIES)

    addView(
        previewView,
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
    )
    themedReactContext.addLifecycleEventListener(this)
    connectivityManager.registerDefaultNetworkCallback(networkCallback)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    post { ensurePreviewRunning() }
  }

  override fun onDetachedFromWindow() {
    stopStreaming()
    stopPreview()
    super.onDetachedFromWindow()
  }

  override fun onHostResume() {
    post {
      isHostPaused = false
      isStoppingStream = false
      ensurePreviewRunning()
      resumeStreamingAfterHostResume()
    }
  }

  override fun onHostPause() {
    isHostPaused = true
    removeCallbacks(reconnectRunnable)
    pauseStreamingForHostPause()
    stopPreview()
  }

  override fun onHostDestroy() {
    cleanup()
  }

  fun cleanup() {
    if (cleanedUp) {
      return
    }
    cleanedUp = true
    themedReactContext.removeLifecycleEventListener(this)
    runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    removeCallbacks(reconnectRunnable)
    removeCallbacks(cameraSwitchRequestRunnable)
    removeCallbacks(cameraSwitchCompleteRunnable)
    stopStreaming()
    stopPreview()
  }

  fun setCamera(camera: String?) {
    desiredCamera = if (camera == "front") CameraMode.FRONT else CameraMode.BACK
    scheduleCameraFacingSync()
  }

  fun setVideoConfig(video: ReadableMap?) {
    if (video == null) {
      return
    }
    val nextConfig =
        VideoConfig(
            fps = video.getIntCompat("fps", videoConfig.fps),
            width = video.getIntCompat("width", videoConfig.width),
            height = video.getIntCompat("height", videoConfig.height),
            bitrate = video.getIntCompat("bitrate", videoConfig.bitrate),
            gopDuration = video.getDoubleCompat("gopDuration", videoConfig.gopDuration),
        )
    val shouldRestartStream = rtmpCamera.isStreaming && nextConfig != videoConfig
    videoConfig = nextConfig
    if (shouldRestartStream) {
      restartStreamingWithCurrentConfig()
      return
    }
    reconfigurePreview()
  }

  fun setAudioConfig(audio: ReadableMap?) {
    if (audio == null) {
      return
    }
    val nextConfig =
        AudioConfig(
            sampleRate = audio.getIntCompat("sampleRate", audioConfig.sampleRate),
            isStereo = audio.getBooleanCompat("isStereo", audioConfig.isStereo),
            bitrate = audio.getIntCompat("bitrate", audioConfig.bitrate),
        )
    val shouldRestartStream = rtmpCamera.isStreaming && nextConfig != audioConfig
    audioConfig = nextConfig
    if (shouldRestartStream) {
      restartStreamingWithCurrentConfig()
      return
    }
    reconfigurePreview()
  }

  fun setMuted(muted: Boolean?) {
    isMuted = muted ?: false
    applyMuteState()
  }

  fun setVideoEnabled(enabled: Boolean?) {
    isVideoEnabled = enabled ?: true
    applyVideoState()
  }

  fun setEnablePinchedZoom(enabled: Boolean?) {
    enablePinchedZoom = enabled ?: false
  }

  fun startStreaming(streamKey: String, baseUrl: String?, camera: String?) {
    setCamera(camera)
    ensurePreviewRunning()
    hasConnectedStream = false
    isStoppingStream = false
    shouldReconnectStream = true
    retryAttempt = 0
    pendingRetryReason = null
    removeCallbacks(reconnectRunnable)
    currentStreamUrl = buildRtmpUrl(baseUrl, streamKey)
    val streamUrl = currentStreamUrl ?: return
    rtmpCamera.setReTries(MAX_RETRIES)

    if (!prepareEncoders()) {
      dispatchConnectionFailed("PREPARE_FAILED")
      return
    }

    if (!ensureDesiredCameraForStreaming()) {
      dispatchConnectionFailed("CAMERA_START_FAILED")
      return
    }
    applyMuteState()

    if (!rtmpCamera.isStreaming) {
      try {
        rtmpCamera.startStream(streamUrl)
      } catch (_: CameraOpenException) {
        dispatchConnectionFailed("UNSUPPORTED_CAMERA_RESOLUTION")
        stopStreaming()
        ensurePreviewRunning()
      } catch (_: RuntimeException) {
        dispatchConnectionFailed("STREAM_START_FAILED")
        stopStreaming()
        ensurePreviewRunning()
      }
    }
  }

  fun stopStreaming() {
    shouldResumeStreamOnHostResume = false
    shouldReconnectStream = false
    isStoppingStream = true
    currentStreamUrl = null
    pendingRetryReason = null
    retryAttempt = 0
    hasPendingCameraSync = false
    removeCallbacks(reconnectRunnable)
    if (rtmpCamera.isStreaming) {
      runCatching { rtmpCamera.stopStream() }
    } else {
      isStoppingStream = false
    }
  }

  fun setZoomRatio(zoomRatio: Double) {
    pendingZoomRatio = zoomRatio
    applyZoomLevel(zoomRatio.roundToInt())
  }

  fun getStreamCapabilities(camera: String?): WritableMap {
    val sizes = if (camera == "front") rtmpCamera.resolutionsFront else rtmpCamera.resolutionsBack
    val curatedResolutions =
        selectStreamResolutions(
            sizes
                .asSequence()
                .filter { it.width > 0 && it.height > 0 }
                .map { resolution -> normalizeResolution(resolution) }
                .distinctBy { "${it.width}x${it.height}" }
                .toList(),
        )
    val resolutions = Arguments.createArray()

    curatedResolutions.forEach { resolution ->
      val maxFps = maxFpsForResolution(resolution)
      resolutions.pushMap(
          Arguments.createMap().apply {
            putInt("width", resolution.width)
            putInt("height", resolution.height)
            putInt("maxFps", maxFps)
          },
      )
    }

    return Arguments.createMap().apply {
      putString("performanceClass", performanceClass())
      putArray("resolutions", resolutions)
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    return if (enablePinchedZoom) {
      scaleGestureDetector.onTouchEvent(event)
      true
    } else {
      super.onTouchEvent(event)
    }
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    return enablePinchedZoom
  }

  override fun onConnectionStartedRtmp(rtmpUrl: String) {
    post { currentStreamUrl = rtmpUrl }
  }

  override fun onConnectionSuccessRtmp() {
    post {
      hasConnectedStream = true
      pendingRetryReason = null
      retryAttempt = 0
      isStoppingStream = false
      removeCallbacks(reconnectRunnable)
      rtmpCamera.setReTries(MAX_RETRIES)
      dispatchSimpleEvent(ConnectionSuccessEvent(surfaceId(), id))
    }
  }

  override fun onConnectionFailedRtmp(reason: String) {
    post {
      if (isHostPaused && shouldResumeStreamOnHostResume) {
        hasConnectedStream = false
        pendingRetryReason = reason
        return@post
      }
      hasConnectedStream = false
      pendingRetryReason = reason
      if (shouldReconnect(reason)) {
        scheduleReconnect(reason, immediate = !isNetworkAvailable)
      } else {
        shouldReconnectStream = false
        dispatchConnectionFailed(reason)
        stopStreaming()
        ensurePreviewRunning()
      }
    }
  }

  override fun onNewBitrateRtmp(bitrate: Long) = Unit

  override fun onDisconnectRtmp() {
    post {
      if (isHostPaused && shouldResumeStreamOnHostResume) {
        hasConnectedStream = false
        return@post
      }
      val shouldAttemptReconnect = hasConnectedStream && shouldReconnectStream && !isStoppingStream
      val shouldEmitDisconnect = hasConnectedStream && !shouldAttemptReconnect && !isStoppingStream
      hasConnectedStream = false
      if (shouldAttemptReconnect) {
        scheduleReconnect(pendingRetryReason ?: "RTMP disconnected", immediate = !isNetworkAvailable)
      } else if (shouldEmitDisconnect) {
        dispatchSimpleEvent(DisconnectEvent(surfaceId(), id))
      }
      isStoppingStream = false
    }
  }

  override fun onAuthErrorRtmp() {
    post {
      hasConnectedStream = false
      shouldReconnectStream = false
      pendingRetryReason = null
      dispatchConnectionFailed("AUTH_ERROR")
      stopStreaming()
      ensurePreviewRunning()
    }
  }

  override fun onAuthSuccessRtmp() = Unit

  private fun reconfigurePreview() {
    if (!isAttachedToWindow || rtmpCamera.isStreaming) {
      return
    }
    stopPreview()
    post { ensurePreviewRunning() }
  }

  private fun ensurePreviewRunning() {
    if (!isAttachedToWindow || rtmpCamera.isStreaming) {
      return
    }
    if (!prepareEncoders()) {
      return
    }
    if (!previewStarted) {
      val facing =
          if (desiredCamera == CameraMode.FRONT) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
      try {
        rtmpCamera.startPreview(facing, videoConfig.width, videoConfig.height, currentVideoRotation())
      } catch (_: CameraOpenException) {
        dispatchConnectionFailed("UNSUPPORTED_CAMERA_RESOLUTION")
        return
      } catch (_: RuntimeException) {
        dispatchConnectionFailed("CAMERA_PREVIEW_FAILED")
        return
      }
      applyPreviewOrientation()
      previewStarted = rtmpCamera.isOnPreview
      applyZoomLevel(pendingZoomRatio.roundToInt())
    } else {
      syncCameraFacing()
      applyPreviewOrientation()
    }
    applyMuteState()
    applyVideoState()
  }

  private fun stopPreview() {
    if (!previewStarted || rtmpCamera.isStreaming) {
      return
    }
    runCatching { rtmpCamera.stopPreview() }
    previewStarted = false
  }

  private fun prepareEncoders(): Boolean {
    return runCatching {
          val preparedVideo =
              rtmpCamera.prepareVideo(
                  videoConfig.width,
                  videoConfig.height,
                  videoConfig.fps,
                  videoConfig.bitrate,
                  videoConfig.gopDuration.roundToInt(),
                  currentVideoRotation(),
              )
          val preparedAudio =
              rtmpCamera.prepareAudio(
                  audioConfig.bitrate,
                  audioConfig.sampleRate,
                  audioConfig.isStereo,
              )
          preparedVideo && preparedAudio
        }
        .getOrDefault(false)
  }

  private fun syncCameraFacing() {
    if (!previewStarted && !rtmpCamera.isStreaming) {
      return
    }

    if (isSwitchingCamera) {
      hasPendingCameraSync = true
      return
    }

    val expectedFacing =
        if (desiredCamera == CameraMode.FRONT) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
    if (rtmpCamera.cameraFacing == expectedFacing) {
      return
    }

    isSwitchingCamera = true
    try {
      val cameraId = cameraIdFor(desiredCamera)
      if (cameraId != null) {
        rtmpCamera.switchCamera(cameraId)
      } else {
        rtmpCamera.switchCamera()
      }
      applyPreviewOrientation()
    } catch (_: CameraOpenException) {
      dispatchConnectionFailed("CAMERA_SWITCH_FAILED")
    } catch (_: RuntimeException) {
      dispatchConnectionFailed("CAMERA_SWITCH_FAILED")
    } finally {
      postDelayed(cameraSwitchCompleteRunnable, CAMERA_SWITCH_COOLDOWN_MS)
    }
  }

  private fun scheduleCameraFacingSync() {
    removeCallbacks(cameraSwitchRequestRunnable)
    postDelayed(cameraSwitchRequestRunnable, CAMERA_SWITCH_DEBOUNCE_MS)
  }

  private fun applyMuteState() {
    runCatching {
      if (isMuted) {
        rtmpCamera.disableAudio()
      } else {
        rtmpCamera.enableAudio()
      }
    }
  }

  private fun ensureDesiredCameraForStreaming(): Boolean {
    if (rtmpCamera.isOnPreview) {
      runCatching { rtmpCamera.stopPreview() }
      previewStarted = false
    }

    return startPreviewWithDesiredCamera()
  }

  private fun startPreviewWithDesiredCamera(): Boolean {
    if (!isAttachedToWindow) {
      return false
    }

    val cameraId = cameraIdFor(desiredCamera)
    return runCatching {
          if (cameraId != null) {
            rtmpCamera.startPreview(cameraId, videoConfig.width, videoConfig.height, currentVideoRotation())
          } else {
            val facing =
                if (desiredCamera == CameraMode.FRONT) CameraHelper.Facing.FRONT
                else CameraHelper.Facing.BACK
            rtmpCamera.startPreview(
                facing,
                videoConfig.width,
                videoConfig.height,
                currentVideoRotation(),
            )
          }
          applyPreviewOrientation()
          previewStarted = rtmpCamera.isOnPreview
          previewStarted
        }
        .getOrDefault(false)
  }

  private fun cameraIdFor(cameraMode: CameraMode): Int? {
    val expectedFacing =
        if (cameraMode == CameraMode.FRONT) Camera.CameraInfo.CAMERA_FACING_FRONT
        else Camera.CameraInfo.CAMERA_FACING_BACK
    val cameraInfo = Camera.CameraInfo()
    for (cameraId in 0 until Camera.getNumberOfCameras()) {
      Camera.getCameraInfo(cameraId, cameraInfo)
      if (cameraInfo.facing == expectedFacing) {
        return cameraId
      }
    }
    return null
  }

  private fun applyZoomLevel(level: Int) {
    if (!previewStarted && !rtmpCamera.isStreaming) {
      return
    }
    val minZoom = rtmpCamera.minZoom
    val maxZoom = rtmpCamera.maxZoom
    if (maxZoom <= 0) {
      return
    }
    val boundedLevel = level.coerceIn(minZoom, maxZoom)
    runCatching { rtmpCamera.setZoom(boundedLevel) }
  }

  private fun applyVideoState() {
    previewView.alpha = if (isVideoEnabled) 1f else 0f
  }

  private fun applyPreviewOrientation() {
    runCatching { rtmpCamera.setPreviewOrientation(currentVideoRotation()) }
  }

  private fun currentVideoRotation(): Int {
    return if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
      90
    } else {
      0
    }
  }

  private fun buildRtmpUrl(baseUrl: String?, streamKey: String): String {
    val normalizedStreamKey = streamKey.trim()
    if (normalizedStreamKey.startsWith("rtmp://")) {
      return normalizedStreamKey
    }

    val normalizedBase = (baseUrl ?: DEFAULT_STREAM_BASE_URL).trim().trimEnd('/')
    if (normalizedStreamKey.isEmpty()) {
      return normalizedBase
    }

    return "$normalizedBase/$normalizedStreamKey"
  }

  private fun dispatchConnectionFailed(code: String) {
    val eventData = Arguments.createMap().apply { putString("code", code) }
    dispatchSimpleEvent(ConnectionFailedEvent(surfaceId(), id, eventData))
  }

  private fun restartStreamingWithCurrentConfig() {
    val streamUrl = currentStreamUrl ?: return
    removeCallbacks(reconnectRunnable)
    hasConnectedStream = false
    isStoppingStream = true
    if (rtmpCamera.isStreaming) {
      runCatching { rtmpCamera.stopStream() }
    }
    post {
      isStoppingStream = false
      if (!prepareEncoders()) {
        dispatchConnectionFailed("PREPARE_FAILED")
        ensurePreviewRunning()
        return@post
      }

      syncCameraFacing()
      applyMuteState()

      try {
        rtmpCamera.startStream(streamUrl)
      } catch (_: CameraOpenException) {
        dispatchConnectionFailed("UNSUPPORTED_CAMERA_RESOLUTION")
        stopStreaming()
        ensurePreviewRunning()
      }
    }
  }

  private fun pauseStreamingForHostPause() {
    shouldResumeStreamOnHostResume = shouldReconnectStream && currentStreamUrl != null
    if (!shouldResumeStreamOnHostResume) {
      isStoppingStream = false
      return
    }

    hasConnectedStream = false
    isStoppingStream = true
    runCatching {
      if (rtmpCamera.isStreaming) {
        rtmpCamera.stopStream()
      }
    }
    isStoppingStream = false
  }

  private fun resumeStreamingAfterHostResume() {
    val streamUrl = currentStreamUrl ?: return
    if (!shouldResumeStreamOnHostResume || rtmpCamera.isStreaming) {
      return
    }

    shouldResumeStreamOnHostResume = false
    retryAttempt = 0
    pendingRetryReason = pendingRetryReason ?: "App resumed"

    if (!isNetworkAvailable) {
      scheduleReconnect(pendingRetryReason ?: "App resumed", immediate = true)
      return
    }

    if (!prepareEncoders()) {
      dispatchConnectionFailed("PREPARE_FAILED")
      ensurePreviewRunning()
      return
    }

    if (!ensureDesiredCameraForStreaming()) {
      dispatchConnectionFailed("CAMERA_START_FAILED")
      ensurePreviewRunning()
      return
    }

    applyMuteState()

    try {
      rtmpCamera.startStream(streamUrl)
    } catch (_: CameraOpenException) {
      dispatchConnectionFailed("UNSUPPORTED_CAMERA_RESOLUTION")
      stopStreaming()
      ensurePreviewRunning()
    } catch (_: RuntimeException) {
      pendingRetryReason = "STREAM_RESUME_FAILED"
      scheduleReconnect(pendingRetryReason ?: "STREAM_RESUME_FAILED", immediate = true)
      ensurePreviewRunning()
    }
  }

  private fun shouldReconnect(reason: String): Boolean {
    return shouldReconnectStream &&
        !isHostPaused &&
        !isStoppingStream &&
        !reason.contains("Endpoint malformed", ignoreCase = true) &&
        !reason.contains("auth", ignoreCase = true) &&
        !reason.contains("Publish.BadName", ignoreCase = true) &&
        !reason.contains("Connect.Rejected", ignoreCase = true)
  }

  private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
    if (!shouldReconnect(reason)) {
      return
    }

    pendingRetryReason = reason
    removeCallbacks(reconnectRunnable)

    if (!isNetworkAvailable) {
      return
    }

    val delayMs = if (immediate) 0L else nextRetryDelayMs()
    postDelayed(reconnectRunnable, delayMs)
  }

  private fun retryStream() {
    val streamUrl = currentStreamUrl ?: return
    val reason = pendingRetryReason ?: "RTMP reconnect"
    if (!shouldReconnect(reason) || !isNetworkAvailable) {
      return
    }

    rtmpCamera.setReTries(MAX_RETRIES)
    val retried = rtmpCamera.reTry(0, reason, streamUrl)
    if (!retried) {
      shouldReconnectStream = false
      dispatchConnectionFailed(reason)
      stopStreaming()
      ensurePreviewRunning()
    }
  }

  private fun nextRetryDelayMs(): Long {
    val delay = (1_000L shl retryAttempt.coerceAtMost(MAX_BACKOFF_SHIFT))
    retryAttempt = (retryAttempt + 1).coerceAtMost(MAX_BACKOFF_SHIFT)
    return delay
  }

  private fun handleNetworkAvailabilityChanged(isAvailable: Boolean) {
    val wasAvailable = isNetworkAvailable
    isNetworkAvailable = isAvailable

    if (!wasAvailable && isAvailable && shouldReconnectStream && !hasConnectedStream) {
      retryAttempt = 0
      post(reconnectRunnable)
    }
  }

  private fun hasActiveNetwork(): Boolean {
    val capabilities =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  private fun performanceClass(): String {
    val activityManager =
        themedReactContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val processors = Runtime.getRuntime().availableProcessors()
    return when {
      activityManager.isLowRamDevice || processors <= 4 -> "low"
      processors <= 6 -> "medium"
      else -> "high"
    }
  }

  private fun maxSupportedFps(): Int {
    return rtmpCamera.supportedFps.maxOfOrNull { fpsRange ->
      (fpsRange.getOrNull(1) ?: fpsRange.getOrNull(0) ?: 30_000) / 1_000
    }?.coerceAtLeast(24) ?: 30
  }

  private fun maxFpsForResolution(resolution: SupportedResolution): Int {
    val supportedMaxFps = maxSupportedFps()
    return when {
      resolution.height >= 2160 -> minOf(supportedMaxFps, 30)
      resolution.height >= 1440 -> minOf(supportedMaxFps, 30)
      resolution.height >= 1080 -> minOf(supportedMaxFps, 30)
      resolution.height >= 720 -> minOf(supportedMaxFps, 60)
      else -> supportedMaxFps
    }.coerceAtLeast(24)
  }

  private fun normalizeResolution(size: Camera.Size): SupportedResolution {
    val maxDimension = maxOf(size.width, size.height)
    val minDimension = minOf(size.width, size.height)
    return SupportedResolution(width = maxDimension, height = minDimension)
  }

  private fun selectStreamResolutions(
      resolutions: List<SupportedResolution>
  ): List<SupportedResolution> {
    if (resolutions.isEmpty()) {
      return emptyList()
    }

    val performanceClass = performanceClass()
    val maxHeight =
        when (performanceClass) {
          "low" -> 720
          "medium" -> 1080
          else -> 2160
        }
    val filtered =
        resolutions
            .filter { it.height >= MIN_STREAM_HEIGHT && it.height <= maxHeight }
            .sortedBy { it.width * it.height }
    val candidates = if (filtered.isNotEmpty()) filtered else resolutions.sortedBy { it.width * it.height }
    val targetHeights = if (performanceClass == "high") STREAM_TARGET_HEIGHTS else STREAM_TARGET_HEIGHTS.filter { it <= maxHeight }
    val selected =
        targetHeights
            .mapNotNull { targetHeight -> closestResolutionForHeight(candidates, targetHeight) }
            .distinctBy { "${it.width}x${it.height}" }
            .sortedBy { it.width * it.height }

    return if (selected.isNotEmpty()) {
      selected
    } else {
      candidates.takeLast(MAX_EXPOSED_RESOLUTIONS)
    }
  }

  private fun closestResolutionForHeight(
      resolutions: List<SupportedResolution>,
      targetHeight: Int
  ): SupportedResolution? {
    return resolutions.minWithOrNull(
        compareBy<SupportedResolution> { kotlin.math.abs(it.height - targetHeight) }
            .thenBy { aspectRatioPenalty(it) }
            .thenByDescending { it.width * it.height },
    )
  }

  private fun aspectRatioPenalty(resolution: SupportedResolution): Int {
    val ratio = resolution.width.toDouble() / resolution.height.toDouble()
    val widescreenPenalty = kotlin.math.abs(ratio - WIDESCREEN_RATIO)
    val classicPenalty = kotlin.math.abs(ratio - CLASSIC_RATIO)
    return (minOf(widescreenPenalty, classicPenalty) * 1_000).roundToInt()
  }

  private fun dispatchSimpleEvent(event: Event<*>) {
    UiThreadUtil.runOnUiThread {
      val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(themedReactContext, id)
      dispatcher?.dispatchEvent(event)
    }
  }

  private fun surfaceId(): Int = UIManagerHelper.getSurfaceId(this)

  private enum class CameraMode {
    FRONT,
    BACK,
  }

  private data class VideoConfig(
      val fps: Int = 24,
      val width: Int = 640,
      val height: Int = 480,
      val bitrate: Int = 1_200_000,
      val gopDuration: Double = 1.0,
  )

  private data class AudioConfig(
      val sampleRate: Int = 44_100,
      val isStereo: Boolean = false,
      val bitrate: Int = 64_000,
  )

  private companion object {
    const val DEFAULT_STREAM_BASE_URL = "rtmp://server/app"
    const val RTMP_CHUNK_SIZE = 4096
    const val MAX_RETRIES = 20
    const val MAX_BACKOFF_SHIFT = 3
    const val CAMERA_SWITCH_DEBOUNCE_MS = 700L
    const val CAMERA_SWITCH_COOLDOWN_MS = 1_000L
    const val MIN_STREAM_HEIGHT = 360
    const val MAX_EXPOSED_RESOLUTIONS = 5
    const val WIDESCREEN_RATIO = 16.0 / 9.0
    const val CLASSIC_RATIO = 4.0 / 3.0
    val STREAM_TARGET_HEIGHTS = listOf(360, 480, 540, 720, 1080, 1440, 2160)
  }

  private val reconnectRunnable = Runnable { retryStream() }
  private val cameraSwitchRequestRunnable = Runnable { syncCameraFacing() }
  private val cameraSwitchCompleteRunnable =
      Runnable {
        isSwitchingCamera = false
        if (hasPendingCameraSync) {
          hasPendingCameraSync = false
          scheduleCameraFacingSync()
        }
      }

  private data class SupportedResolution(val width: Int, val height: Int)
}

private fun ReadableMap.getIntCompat(key: String, fallback: Int): Int {
  if (!hasKey(key) || isNull(key)) {
    return fallback
  }
  return getDouble(key).roundToInt()
}

private fun ReadableMap.getDoubleCompat(key: String, fallback: Double): Double {
  if (!hasKey(key) || isNull(key)) {
    return fallback
  }
  return getDouble(key)
}

private fun ReadableMap.getBooleanCompat(key: String, fallback: Boolean): Boolean {
  if (!hasKey(key) || isNull(key)) {
    return fallback
  }
  return getBoolean(key)
}

internal class ConnectionSuccessEvent(surfaceId: Int, viewTag: Int) :
    Event<ConnectionSuccessEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = EVENT_NAME

  override fun getEventData(): WritableMap = Arguments.createMap()

  companion object {
    const val EVENT_NAME = "topConnectionSuccess"
  }
}

internal class ConnectionFailedEvent(surfaceId: Int, viewTag: Int, private val payload: WritableMap) :
    Event<ConnectionFailedEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = EVENT_NAME

  override fun getEventData(): WritableMap = payload

  companion object {
    const val EVENT_NAME = "topConnectionFailed"
  }
}

internal class DisconnectEvent(surfaceId: Int, viewTag: Int) :
    Event<DisconnectEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = EVENT_NAME

  override fun getEventData(): WritableMap = Arguments.createMap()

  companion object {
    const val EVENT_NAME = "topDisconnect"
  }
}
