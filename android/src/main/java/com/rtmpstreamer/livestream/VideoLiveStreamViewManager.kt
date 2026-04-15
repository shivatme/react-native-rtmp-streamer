package com.rtmpstreamer.livestream

import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.viewmanagers.VideoLiveStreamViewManagerDelegate
import com.facebook.react.viewmanagers.VideoLiveStreamViewManagerInterface

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class VideoLiveStreamViewManager :
    SimpleViewManager<VideoLiveStreamView>(),
    VideoLiveStreamViewManagerInterface<VideoLiveStreamView> {

  private val delegate: ViewManagerDelegate<VideoLiveStreamView> =
      VideoLiveStreamViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<VideoLiveStreamView> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(reactContext: ThemedReactContext): VideoLiveStreamView =
      VideoLiveStreamView(reactContext)

  override fun onDropViewInstance(view: VideoLiveStreamView) {
    view.cleanup()
    super.onDropViewInstance(view)
  }

  override fun setCamera(view: VideoLiveStreamView, camera: String?) {
    view.setCamera(camera)
  }

  override fun setVideo(view: VideoLiveStreamView, video: ReadableMap?) {
    view.setVideoConfig(video)
  }

  override fun setAudio(view: VideoLiveStreamView, audio: ReadableMap?) {
    view.setAudioConfig(audio)
  }

  override fun setIsMuted(view: VideoLiveStreamView, isMuted: Boolean) {
    view.setMuted(isMuted)
  }

  override fun setIsVideoEnabled(view: VideoLiveStreamView, isVideoEnabled: Boolean) {
    view.setVideoEnabled(isVideoEnabled)
  }

  override fun setEnablePinchedZoom(view: VideoLiveStreamView, enablePinchedZoom: Boolean) {
    view.setEnablePinchedZoom(enablePinchedZoom)
  }

override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> =
  mutableMapOf(
    ConnectionSuccessEvent.EVENT_NAME to MapBuilder.of("registrationName", "onConnectionSuccess"),
    ConnectionFailedEvent.EVENT_NAME to MapBuilder.of("registrationName", "onConnectionFailed"),
    DisconnectEvent.EVENT_NAME to MapBuilder.of("registrationName", "onDisconnect"),
  )

  companion object {
    const val NAME = "VideoLiveStreamView"
  }
}
