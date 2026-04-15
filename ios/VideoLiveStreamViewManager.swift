import React
import UIKit

@objc(VideoLiveStreamViewManager)
final class VideoLiveStreamViewManager: RCTViewManager {
  override func view() -> UIView! {
    MainActor.assumeIsolated {
      VideoLiveStreamView()
    }
  }

  override static func requiresMainQueueSetup() -> Bool {
    true
  }
}
