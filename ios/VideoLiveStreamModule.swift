import React
import UIKit

@objc(VideoLiveStreamModule)
final class VideoLiveStreamModule: NSObject {
  @objc weak var bridge: RCTBridge?
  @objc var methodQueue: DispatchQueue {
    return bridge?.uiManager.methodQueue ?? DispatchQueue.main
  }

  @objc
  static func requiresMainQueueSetup() -> Bool {
    true
  }

  @objc
  func startStreaming(_ viewTag: NSNumber, streamKey: NSString, url: NSString?, camera: NSString?) {
    let streamKeyValue = streamKey as String
    let urlValue = url as String?
    let cameraValue = camera as String?

    withView(viewTag) { view in
      view.camera = cameraValue as NSString?
      view.startStreaming(streamKey: streamKeyValue, baseUrl: urlValue)
    }
  }

  @objc
  func stopStreaming(_ viewTag: NSNumber) {
    withView(viewTag) { view in
      view.stopStreaming()
    }
  }

  @objc
  func setZoomRatio(_ viewTag: NSNumber, zoomRatio: NSNumber) {
    withView(viewTag) { view in
      view.setZoomRatio(CGFloat(zoomRatio.doubleValue))
    }
  }

  @objc
  func getStreamCapabilities(
    _ viewTag: NSNumber,
    camera: NSString?,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    let cameraValue = camera as String?
    guard let uiManager = bridge?.uiManager else {
      reject("VideoLiveStreamModule", "bridge/uiManager not available", nil)
      return
    }

    uiManager.addUIBlock { _, viewRegistry in
      guard let view = Self.resolveView(viewTag, uiManager: uiManager, viewRegistry: viewRegistry) else {
        reject(
          "VideoLiveStreamModule",
          "Unable to find VideoLiveStreamView for reactTag \(viewTag)",
          nil
        )
        return
      }

      resolve(view.getStreamCapabilities(camera: cameraValue))
    }
  }

  private func withView(
    _ viewTag: NSNumber,
    action: @MainActor @escaping (VideoLiveStreamView) -> Void
  ) {
    guard let uiManager = bridge?.uiManager else {
      NSLog("bridge/uiManager not available")
      return
    }

    uiManager.addUIBlock { _, viewRegistry in
      guard let view = Self.resolveView(viewTag, uiManager: uiManager, viewRegistry: viewRegistry) else {
        NSLog("Unable to find VideoLiveStreamView for reactTag \(viewTag)")
        return
      }

      Task { @MainActor in
        action(view)
      }
    }
  }

  private static func resolveView(
    _ viewTag: NSNumber,
    uiManager: RCTUIManager,
    viewRegistry: [NSNumber: UIView]?
  ) -> VideoLiveStreamView? {
    if let directView = viewRegistry?[viewTag] {
      let resolvedView = RCTPaperViewOrCurrentView(directView)
      if let streamView = resolvedView as? VideoLiveStreamView {
        return streamView
      }
    }

    if let managerView = uiManager.view(forReactTag: viewTag) {
      let resolvedView = RCTPaperViewOrCurrentView(managerView)
      if let streamView = resolvedView as? VideoLiveStreamView {
        return streamView
      }
    }

    return nil
  }
}
