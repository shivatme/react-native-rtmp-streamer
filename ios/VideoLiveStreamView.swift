@preconcurrency import AVFoundation
import HaishinKit
import Network
import React
import UIKit
import VideoToolbox

@MainActor
@objc(VideoLiveStreamView)
final class VideoLiveStreamView: UIView {
  @objc var camera: NSString? {
    didSet {
      desiredCameraPosition = (camera as String?)?.lowercased() == "front" ? .front : .back
      reattachVideoInput()
    }
  }

  @objc var video: NSDictionary? {
    didSet {
      videoConfig = VideoConfig(dictionary: video)
      Task {
        try? await applyVideoSettings()
      }
    }
  }

  @objc var audio: NSDictionary? {
    didSet {
      audioConfig = AudioConfig(dictionary: audio)
      Task {
        try? await applyAudioSettings()
      }
    }
  }

  @objc var isMuted: NSNumber? {
    didSet {
      muted = isMuted?.boolValue ?? false
      Task {
        try? await applyAudioSettings()
      }
    }
  }

  @objc var isVideoEnabled: NSNumber? {
    didSet {
      videoEnabled = isVideoEnabled?.boolValue ?? true
      previewView.alpha = videoEnabled ? 1 : 0
      Task {
        await applyVideoMixerSettings()
      }
    }
  }

  @objc var enablePinchedZoom: NSNumber? {
    didSet {
      pinchGestureRecognizer.isEnabled = enablePinchedZoom?.boolValue ?? false
    }
  }

  @objc var onConnectionSuccess: RCTDirectEventBlock?
  @objc var onConnectionFailed: RCTDirectEventBlock?
  @objc var onDisconnect: RCTDirectEventBlock?

  private let mixer = MediaMixer()
  private let connection = RTMPConnection(
    timeout: 20,
    requestTimeout: 10_000,
    chunkSize: 4096,
  )
  private lazy var stream = RTMPStream(connection: connection)
  private let previewView = MTHKView(frame: .zero)
  private let networkMonitor = NWPathMonitor()
  private let networkMonitorQueue = DispatchQueue(
    label: "com.nativertmp.VideoLiveStreamView.network",
  )
  private lazy var pinchGestureRecognizer = UIPinchGestureRecognizer(
    target: self,
    action: #selector(handlePinch(_:)),
  )

  private var setupTask: Task<Void, Never>?
  private var connectionStatusTask: Task<Void, Never>?
  private var connectTask: Task<Void, Never>?
  private var reconnectTask: Task<Void, Never>?
  private var desiredCameraPosition: AVCaptureDevice.Position = .back
  private var currentVideoDevice: AVCaptureDevice?
  private var videoConfig = VideoConfig()
  private var audioConfig = AudioConfig()
  private var muted = false
  private var videoEnabled = true
  private var pendingZoomRatio: CGFloat = 1
  private var pipelineReady = false
  private var hasConnectedStream = false
  private var isStoppingStream = false
  private var shouldReconnectStream = false
  private var isConnectingStream = false
  private var isNetworkAvailable = true
  private var reconnectAttempt = 0
  private var pendingStreamTarget: StreamTarget?

  override init(frame: CGRect) {
    super.init(frame: frame)
    configureView()
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    configureView()
  }

  deinit {
    connectTask?.cancel()
    reconnectTask?.cancel()
    connectionStatusTask?.cancel()
    setupTask?.cancel()
    networkMonitor.cancel()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    previewView.frame = bounds
    Task {
      await mixer.setVideoOrientation(currentVideoOrientation())
    }
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window != nil {
      if pipelineReady {
        Task {
          await mixer.startRunning()
        }
      } else {
        ensurePipelineReady()
      }
    } else {
      Task {
        await stopPreview()
      }
    }
  }

func startStreaming(streamKey: String, baseUrl: String?) {
  NSLog("[RTMP] startStreaming called")
  NSLog("[RTMP] streamKey: \(streamKey)")
  NSLog("[RTMP] baseUrl: \(baseUrl ?? "nil")")

  ensurePipelineReady()
  NSLog("[RTMP] ensurePipelineReady triggered")

  guard let target = makeStreamTarget(streamKey: streamKey, baseUrl: baseUrl) else {
    NSLog("[RTMP] ❌ Invalid stream target (URL parsing failed)")
    emitConnectionFailed(code: "INVALID_URL")
    return
  }

  NSLog("[RTMP] ✅ Stream target created")
  NSLog("[RTMP] connectURL: \(target.connectURL.absoluteString)")
  NSLog("[RTMP] publishName: \(target.publishName ?? "nil")")

  pendingStreamTarget = target
  shouldReconnectStream = true
  reconnectAttempt = 0

  NSLog("[RTMP] Reset reconnect state")

  reconnectTask?.cancel()
  connectTask?.cancel()

  NSLog("[RTMP] Cancelled existing tasks")

  connectTask = Task { [weak self] in
    guard let self else {
      NSLog("[RTMP] ❌ self deallocated before connect")
      return
    }

    NSLog("[RTMP] 🚀 Starting connectStream task")
    await self.connectStream(to: target)
  }
}

  func stopStreaming() {
    Task {
      await stopStreamingInternal()
    }
  }

  func setZoomRatio(_ zoomRatio: CGFloat) {
    pendingZoomRatio = max(1, zoomRatio)
    applyZoomRatio()
  }

  nonisolated func getStreamCapabilities(camera: String?) -> [String: Any] {
    let position: AVCaptureDevice.Position = camera?.lowercased() == "front" ? .front : .back
    guard let device = AVCaptureDevice.default(
      .builtInWideAngleCamera,
      for: .video,
      position: position,
    ) ?? AVCaptureDevice.default(for: .video) else {
      return [
        "performanceClass": performanceClass(),
        "resolutions": [],
      ]
    }

    var resolutions: [String: [String: Int]] = [:]
    for format in device.formats {
      let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
      let width = Int(max(dimensions.width, dimensions.height))
      let height = Int(min(dimensions.width, dimensions.height))
      guard width > 0, height > 0 else {
        continue
      }

      let maxFrameRate = Int(
        format.videoSupportedFrameRateRanges
          .map(\.maxFrameRate)
          .max()?
          .rounded(.down) ?? 30
      )
      let key = "\(width)x\(height)"
      let current = resolutions[key]

      if current == nil || maxFrameRate > (current?["maxFps"] ?? 0) {
        resolutions[key] = [
          "width": width,
          "height": height,
          "maxFps": max(maxFrameRate, 24),
        ]
      }
    }

    let curatedResolutions = selectStreamResolutions(
      Array(resolutions.values).compactMap { values in
        guard
          let width = values["width"],
          let height = values["height"],
          let maxFps = values["maxFps"]
        else {
          return nil
        }

        return SupportedResolution(width: width, height: height, maxFps: maxFps)
      },
      performanceClass: performanceClass(),
    )

    return [
      "performanceClass": performanceClass(),
      "resolutions": curatedResolutions.map { resolution in
        [
          "width": resolution.width,
          "height": resolution.height,
          "maxFps": resolution.maxFps,
        ]
      },
    ]
  }

  private func configureView() {
    backgroundColor = .black

    previewView.frame = bounds
    previewView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    previewView.videoGravity = .resizeAspectFill
    addSubview(previewView)

    pinchGestureRecognizer.isEnabled = false
    addGestureRecognizer(pinchGestureRecognizer)

    previewView.alpha = videoEnabled ? 1 : 0

    startNetworkMonitoring()
    observeConnectionStatus()
    ensurePipelineReady()
  }

  private func ensurePipelineReady() {
    guard setupTask == nil, !pipelineReady else {
      return
    }

    setupTask = Task { [weak self] in
      guard let self else {
        return
      }

      do {
        try configureAudioSession()
        try await attachInputs()
        await mixer.addOutput(stream)
        await stream.addOutput(previewView)
        
        let strategy =
          HKStreamVideoAdaptiveBitRateStrategy(
            mamimumVideoBitrate: videoConfig.bitrate
          )

        await stream.setBitrateStorategy(strategy)
        pipelineReady = true
        try await applyVideoSettings()
        try await applyAudioSettings()
        await mixer.setVideoOrientation(currentVideoOrientation())
        await mixer.startRunning()
      } catch {
        pipelineReady = false
        emitConnectionFailed(code: streamErrorCode(from: error))
      }

      setupTask = nil
    }
  }

  private func waitForSetup() async {
    _ = await setupTask?.result
  }

  private func configureAudioSession() throws {
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(
      .playAndRecord,
      mode: .default,
      options: [.defaultToSpeaker, .allowBluetooth],
    )
    try session.setActive(true)
  }

  private func attachInputs() async throws {
    try await attachAudioInput()
    try await attachVideoInput()
  }

  private func attachAudioInput() async throws {
    try await mixer.attachAudio(AVCaptureDevice.default(for: .audio))
  }

  private func attachVideoInput() async throws {
    guard let device = currentCameraDevice() else {
      throw NSError(
        domain: "VideoLiveStreamView",
        code: -1,
        userInfo: [NSLocalizedDescriptionKey: "Camera device unavailable"],
      )
    }

    currentVideoDevice = device

    try await mixer.attachVideo(device) { [desiredCameraPosition] videoUnit in
      videoUnit.isVideoMirrored = desiredCameraPosition == .front
      videoUnit.preferredVideoStabilizationMode = .standard
    }

    applyZoomRatio()
  }

  private func reattachVideoInput() {
    guard pipelineReady else {
      return
    }

    Task {
      do {
        try await attachVideoInput()
        await mixer.setVideoOrientation(currentVideoOrientation())
      } catch {
        emitConnectionFailed(code: streamErrorCode(from: error))
      }
    }
  }

  private func applyVideoSettings() async throws {
    guard pipelineReady else {
      return
    }

    try await mixer.setFrameRate(Float64(videoConfig.fps))

    var mixerSettings = VideoMixerSettings()
    mixerSettings.isMuted = !videoEnabled
    mixerSettings.mainTrack = 0
    await mixer.setVideoMixerSettings(mixerSettings)

    let codecSettings = VideoCodecSettings(
      videoSize: CGSize(width: videoConfig.width, height: videoConfig.height),
      bitRate: videoConfig.bitrate,
      profileLevel: profileLevelForVideoConfig(),
      scalingMode: .letterbox,
      bitRateMode: .average,
      maxKeyFrameIntervalDuration: Int32(max(1, round(videoConfig.gopDuration))),
      allowFrameReordering: nil,
      dataRateLimits: [0.0, 0.0],
      isHardwareEncoderEnabled: true,
    )
    await stream.setVideoSettings(codecSettings)
    
    let strategy =
      HKStreamVideoAdaptiveBitRateStrategy(
        mamimumVideoBitrate: videoConfig.bitrate
      )

    await stream.setBitrateStorategy(strategy)
   
  }

  private func applyAudioSettings() async throws {
    guard pipelineReady else {
      return
    }

    var mixerSettings = AudioMixerSettings(
      sampleRate: Float64(audioConfig.sampleRate),
      channels: audioConfig.isStereo ? 2 : 1,
    )
    mixerSettings.tracks = [
      0: .init(
        isMuted: muted,
        downmix: !audioConfig.isStereo,
      ),
    ]
    await mixer.setAudioMixerSettings(mixerSettings)

    let codecSettings = AudioCodecSettings(
      bitRate: audioConfig.bitrate,
      downmix: !audioConfig.isStereo,
      sampleRate: Float64(audioConfig.sampleRate),
      format: .aac,
    )
    try await stream.setAudioSettings(codecSettings)
  }

  private func applyVideoMixerSettings() async {
    guard pipelineReady else {
      return
    }

    var mixerSettings = VideoMixerSettings()
    mixerSettings.isMuted = !videoEnabled
    mixerSettings.mainTrack = 0
    await mixer.setVideoMixerSettings(mixerSettings)
  }

  private func stopStreamingInternal() async {
    shouldReconnectStream = false
    pendingStreamTarget = nil
    reconnectAttempt = 0
    connectTask?.cancel()
    reconnectTask?.cancel()
    isStoppingStream = true

    let isConnectionActive = await connection.connected
    if hasConnectedStream || isConnectionActive {
      do {
        _ = try await stream.close()
      } catch {}
    }

    do {
      try await connection.close()
    } catch {}

    hasConnectedStream = false
    isConnectingStream = false
    isStoppingStream = false
  }

  private func stopPreview() async {
    await stopStreamingInternal()
    guard pipelineReady else {
      return
    }
    await mixer.stopRunning()
  }

  private func observeConnectionStatus() {
    guard connectionStatusTask == nil else {
      return
    }

    connectionStatusTask = Task { [weak self] in
      guard let self else {
        return
      }

      let statuses = await connection.status
      for await status in statuses {
        handleConnectionStatus(status)
      }
    }
  }

  private func handleConnectionStatus(_ status: RTMPStatus) {
    let code = status.code

    if code == RTMPConnection.Code.connectSuccess.rawValue {
      reconnectAttempt = 0
      return
    }

    if code == RTMPConnection.Code.connectFailed.rawValue {
      hasConnectedStream = false
      isStoppingStream = false
      if shouldReconnectStream {
        scheduleReconnect()
      } else {
        emitConnectionFailed(code: code)
      }
      return
    }

    if code == RTMPConnection.Code.connectClosed.rawValue {
      let shouldRecover = hasConnectedStream && shouldReconnectStream && !isStoppingStream
      let shouldEmitDisconnect = hasConnectedStream && !shouldRecover && !isStoppingStream
      hasConnectedStream = false
      isStoppingStream = false
      if shouldRecover {
        scheduleReconnect(immediate: isNetworkAvailable)
      } else if shouldEmitDisconnect {
        onDisconnect?([:])
      }
      return
    }

    if code == RTMPConnection.Code.connectNetworkChange.rawValue,
      shouldReconnectStream,
      !hasConnectedStream,
      !isStoppingStream
    {
      scheduleReconnect(immediate: true)
    }
  }

  private func connectStream(to target: StreamTarget) async {
    await waitForSetup()

    guard pipelineReady else {
      emitConnectionFailed(code: "PREPARE_FAILED")
      return
    }

    guard shouldReconnectStream, pendingStreamTarget == target else {
      return
    }

    guard !isConnectingStream else {
      return
    }

    isConnectingStream = true
    isStoppingStream = false

    defer {
      isConnectingStream = false
    }

    do {
      _ = try await connection.connect(target.connectURL.absoluteString)
      _ = try await stream.publish(target.publishName)
      guard shouldReconnectStream else {
        await resetConnectionForRetry()
        return
      }
      hasConnectedStream = true
      reconnectAttempt = 0
      onConnectionSuccess?([:])
    } catch {
      hasConnectedStream = false
      isStoppingStream = false

      if shouldReconnectStream && shouldRetryConnection(after: error) {
        await resetConnectionForRetry()
        scheduleReconnect()
      } else {
        shouldReconnectStream = false
        pendingStreamTarget = nil
        reconnectAttempt = 0
        emitConnectionFailed(code: streamErrorCode(from: error))
      }
    }
  }

  private func resetConnectionForRetry() async {
    isStoppingStream = true

    let isConnectionActive = await connection.connected
    if hasConnectedStream || isConnectionActive {
      _ = try? await stream.close()
      try? await connection.close()
    }

    hasConnectedStream = false
    isStoppingStream = false
  }

  private func scheduleReconnect(immediate: Bool = false) {
    guard shouldReconnectStream, pendingStreamTarget != nil else {
      return
    }

    reconnectTask?.cancel()

    guard isNetworkAvailable else {
      return
    }

    let delaySeconds: Double
    if immediate {
      delaySeconds = 0
    } else {
      delaySeconds = min(pow(2.0, Double(reconnectAttempt)), 5.0)
    }

    reconnectAttempt += 1

    reconnectTask = Task { [weak self] in
      if delaySeconds > 0 {
        try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
      }

      guard !Task.isCancelled else {
        return
      }

      await self?.retryPendingConnection()
    }
  }

  private func retryPendingConnection() async {
    guard shouldReconnectStream, isNetworkAvailable, let target = pendingStreamTarget else {
      return
    }

    await connectStream(to: target)
  }

  private func startNetworkMonitoring() {
    networkMonitor.pathUpdateHandler = { [weak self] path in
      Task { @MainActor in
        self?.handleNetworkPathUpdate(path)
      }
    }
    networkMonitor.start(queue: networkMonitorQueue)
  }

  private func handleNetworkPathUpdate(_ path: NWPath) {
    let isAvailable = path.status == .satisfied
    let becameAvailable = !isNetworkAvailable && isAvailable
    isNetworkAvailable = isAvailable

    guard becameAvailable, shouldReconnectStream, !hasConnectedStream, !isConnectingStream else {
      return
    }

    scheduleReconnect(immediate: true)
  }

  private func shouldRetryConnection(after error: Error) -> Bool {
    switch error {
    case let connectionError as RTMPConnection.Error:
      switch connectionError {
      case .connectionTimedOut, .socketErrorOccurred, .requestTimedOut:
        return true
      case .requestFailed(let response):
        return shouldRetryStatusCode(response.status?.code)
      case .invalidState, .unsupportedCommand:
        return false
      }
    case let streamError as RTMPStream.Error:
      switch streamError {
      case .requestTimedOut:
        return true
      case .requestFailed(let response):
        return shouldRetryStatusCode(response.status?.code)
      case .invalidState:
        return false
      }
    default:
      return shouldRetryStatusCode(streamErrorCode(from: error))
    }
  }

  private func shouldRetryStatusCode(_ code: String?) -> Bool {
    switch code {
    case RTMPConnection.Code.connectClosed.rawValue,
      RTMPConnection.Code.connectFailed.rawValue,
      RTMPConnection.Code.connectIdleTimeOut.rawValue,
      RTMPConnection.Code.connectNetworkChange.rawValue,
      RTMPStream.Code.connectClosed.rawValue,
      RTMPStream.Code.connectFailed.rawValue,
      RTMPStream.Code.failed.rawValue:
      return true
    default:
      return false
    }
  }

  @objc
  private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
    switch gesture.state {
    case .began, .changed:
      setZoomRatio(pendingZoomRatio * gesture.scale)
      gesture.scale = 1
    default:
      break
    }
  }

  private func applyZoomRatio() {
    guard let device = currentVideoDevice else {
      return
    }

    let maxZoom = max(1, min(device.activeFormat.videoMaxZoomFactor, 6))
    let zoom = min(max(1, pendingZoomRatio), maxZoom)

    do {
      try device.lockForConfiguration()
      device.videoZoomFactor = zoom
      device.unlockForConfiguration()
      pendingZoomRatio = zoom
    } catch {}
  }

  private func currentCameraDevice() -> AVCaptureDevice? {
    AVCaptureDevice.default(
      .builtInWideAngleCamera,
      for: .video,
      position: desiredCameraPosition,
    ) ?? AVCaptureDevice.default(for: .video)
  }

  nonisolated private func performanceClass() -> String {
    let processInfo = ProcessInfo.processInfo
    let memoryInGB = Double(processInfo.physicalMemory) / 1_073_741_824
    let processors = processInfo.processorCount

    switch (memoryInGB, processors) {
    case let (memory, cpu) where memory < 3 || cpu <= 4:
      return "low"
    case let (memory, cpu) where memory < 6 || cpu <= 6:
      return "medium"
    default:
      return "high"
    }
  }

  nonisolated private func selectStreamResolutions(
    _ resolutions: [SupportedResolution],
    performanceClass: String
  ) -> [SupportedResolution] {
    guard !resolutions.isEmpty else {
      return []
    }

    let maxHeight: Int
    switch performanceClass {
    case "low":
      maxHeight = 720
    case "medium":
      maxHeight = 1080
    default:
      maxHeight = 2160
    }

    let filtered = resolutions
      .filter { $0.height >= 360 && $0.height <= maxHeight }
      .sorted { ($0.width * $0.height) < ($1.width * $1.height) }
    let candidates = filtered.isEmpty
      ? resolutions.sorted { ($0.width * $0.height) < ($1.width * $1.height) }
      : filtered
    let targetHeights = performanceClass == "high"
      ? streamTargetHeights
      : streamTargetHeights.filter { $0 <= maxHeight }
    let selected = targetHeights.compactMap { targetHeight in
      closestResolution(for: targetHeight, from: candidates)
    }

    let unique = uniqueResolutions(selected)
    if !unique.isEmpty {
      return unique
    }

    return Array(candidates.suffix(5))
  }

  nonisolated private func closestResolution(
    for targetHeight: Int,
    from resolutions: [SupportedResolution]
  ) -> SupportedResolution? {
    resolutions.min {
      let leftKey = resolutionRankingKey(for: $0, targetHeight: targetHeight)
      let rightKey = resolutionRankingKey(for: $1, targetHeight: targetHeight)
      return leftKey.lexicographicallyPrecedes(rightKey)
    }
  }

  nonisolated private func resolutionRankingKey(
    for resolution: SupportedResolution,
    targetHeight: Int
  ) -> [Int] {
    [
      abs(resolution.height - targetHeight),
      aspectRatioPenalty(for: resolution),
      -(resolution.width * resolution.height),
    ]
  }

  nonisolated private func aspectRatioPenalty(for resolution: SupportedResolution) -> Int {
    let ratio = Double(resolution.width) / Double(resolution.height)
    let widescreenPenalty = abs(ratio - (16.0 / 9.0))
    let classicPenalty = abs(ratio - (4.0 / 3.0))
    return Int((min(widescreenPenalty, classicPenalty) * 1_000).rounded())
  }

  nonisolated private func uniqueResolutions(
    _ resolutions: [SupportedResolution]
  ) -> [SupportedResolution] {
    var seen = Set<String>()
    return resolutions
      .sorted { ($0.width * $0.height) < ($1.width * $1.height) }
      .filter { resolution in
        let key = "\(resolution.width)x\(resolution.height)"
        return seen.insert(key).inserted
      }
  }

  private func profileLevelForVideoConfig() -> String {
    let maxDimension = max(videoConfig.width, videoConfig.height)
    if maxDimension > 1920 {
      return kVTProfileLevel_H264_High_AutoLevel as String
    }
    if maxDimension > 1280 {
      return kVTProfileLevel_H264_Main_AutoLevel as String
    }
    return kVTProfileLevel_H264_Baseline_3_1 as String
  }

  private func emitConnectionFailed(code: String) {
    onConnectionFailed?(["code": code])
  }

  private func currentVideoOrientation() -> AVCaptureVideoOrientation {
    guard let orientation = window?.windowScene?.interfaceOrientation else {
      return .portrait
    }

    switch orientation {
    case .landscapeLeft:
      return .landscapeLeft
    case .landscapeRight:
      return .landscapeRight
    case .portraitUpsideDown:
      return .portraitUpsideDown
    default:
      return .portrait
    }
  }

  private func makeStreamTarget(streamKey: String, baseUrl: String?) -> StreamTarget? {
    let trimmedStreamKey = streamKey.trimmingCharacters(in: .whitespacesAndNewlines)

    if trimmedStreamKey.hasPrefix("rtmp://") || trimmedStreamKey.hasPrefix("rtmps://") {
      return StreamTarget(absoluteURLString: trimmedStreamKey)
    }

    let trimmedBaseUrl = baseUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard let connectURL = URL(string: trimmedBaseUrl), !trimmedBaseUrl.isEmpty else {
      return nil
    }

    let publishName = trimmedStreamKey.isEmpty ? nil : trimmedStreamKey
    return StreamTarget(connectURL: connectURL, publishName: publishName)
  }

  private func streamErrorCode(from error: Error) -> String {
    if case let RTMPConnection.Error.requestFailed(response) = error {
      return response.status?.code ?? String(describing: error)
    }

    if case let RTMPStream.Error.requestFailed(response) = error {
      return response.status?.code ?? String(describing: error)
    }

    let description = String(describing: error)
    if description.isEmpty {
      return "STREAM_ERROR"
    }
    return description
  }
}

private struct StreamTarget: Equatable {
  let connectURL: URL
  let publishName: String?

  init(connectURL: URL, publishName: String?) {
    self.connectURL = connectURL
    self.publishName = publishName?.isEmpty == true ? nil : publishName
  }

  init?(absoluteURLString: String) {
    guard let components = URLComponents(string: absoluteURLString) else {
      return nil
    }

    let pathSegments = components.path.split(separator: "/").map(String.init)
    let publishSegment = pathSegments.last

    var connectComponents = components
    if !pathSegments.isEmpty {
      let baseSegments = pathSegments.dropLast()
      connectComponents.percentEncodedPath =
        baseSegments.isEmpty ? "" : "/" + baseSegments.joined(separator: "/")
      connectComponents.percentEncodedQuery = nil
      connectComponents.fragment = nil
    }

    guard let connectURL = connectComponents.url else {
      return nil
    }

    var publishName = publishSegment
    if let query = components.percentEncodedQuery, !query.isEmpty {
      if var name = publishName {
        name += "?\(query)"
        publishName = name
      }
    }

    self.init(connectURL: connectURL, publishName: publishName)
  }
}

private struct SupportedResolution {
  let width: Int
  let height: Int
  let maxFps: Int
}

private let streamTargetHeights = [360, 480, 540, 720, 1080, 1440, 2160]

private struct VideoConfig {
  let fps: Int
  let width: Int
  let height: Int
  let bitrate: Int
  let gopDuration: Double

  init(
    fps: Int = 24,
    width: Int = 640,
    height: Int = 480,
    bitrate: Int = 1_200_000,
    gopDuration: Double = 1,
  ) {
    self.fps = fps
    self.width = width
    self.height = height
    self.bitrate = bitrate
    self.gopDuration = gopDuration
  }

  init(dictionary: NSDictionary?) {
    self.init(
      fps: dictionary?.intValue(forKey: "fps", fallback: 24) ?? 24,
      width: dictionary?.intValue(forKey: "width", fallback: 640) ?? 640,
      height: dictionary?.intValue(forKey: "height", fallback: 480) ?? 480,
      bitrate: dictionary?.intValue(forKey: "bitrate", fallback: 1_200_000) ?? 1_200_000,
      gopDuration: dictionary?.doubleValue(forKey: "gopDuration", fallback: 1) ?? 1,
    )
  }
}

private struct AudioConfig {
  let sampleRate: Int
  let isStereo: Bool
  let bitrate: Int

  init(sampleRate: Int = 44_100, isStereo: Bool = false, bitrate: Int = 64_000) {
    self.sampleRate = sampleRate
    self.isStereo = isStereo
    self.bitrate = bitrate
  }

  init(dictionary: NSDictionary?) {
    self.init(
      sampleRate: dictionary?.intValue(forKey: "sampleRate", fallback: 44_100) ?? 44_100,
      isStereo: dictionary?.boolValue(forKey: "isStereo", fallback: false) ?? false,
      bitrate: dictionary?.intValue(forKey: "bitrate", fallback: 64_000) ?? 64_000,
    )
  }
}

private extension NSDictionary {
  func intValue(forKey key: String, fallback: Int) -> Int {
    guard let value = self[key] else {
      return fallback
    }
    if let number = value as? NSNumber {
      return number.intValue
    }
    return fallback
  }

  func doubleValue(forKey key: String, fallback: Double) -> Double {
    guard let value = self[key] else {
      return fallback
    }
    if let number = value as? NSNumber {
      return number.doubleValue
    }
    return fallback
  }

  func boolValue(forKey key: String, fallback: Bool) -> Bool {
    guard let value = self[key] else {
      return fallback
    }
    if let number = value as? NSNumber {
      return number.boolValue
    }
    return fallback
  }
}
