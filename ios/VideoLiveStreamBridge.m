#import <React/RCTBridgeModule.h>
#import <React/RCTViewManager.h>

@interface RCT_EXTERN_MODULE(VideoLiveStreamModule, NSObject)

RCT_EXTERN_METHOD(startStreaming:(nonnull NSNumber *)viewTag
                  streamKey:(NSString *)streamKey
                  url:(nullable NSString *)url
                  camera:(nullable NSString *)camera)
RCT_EXTERN_METHOD(stopStreaming:(nonnull NSNumber *)viewTag)
RCT_EXTERN_METHOD(setZoomRatio:(nonnull NSNumber *)viewTag
                  zoomRatio:(nonnull NSNumber *)zoomRatio)
RCT_EXTERN_METHOD(getStreamCapabilities:(nonnull NSNumber *)viewTag
                  camera:(nullable NSString *)camera
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

@end

@interface RCT_EXTERN_MODULE(VideoLiveStreamViewManager, RCTViewManager)

RCT_EXPORT_VIEW_PROPERTY(camera, NSString)
RCT_EXPORT_VIEW_PROPERTY(video, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(audio, NSDictionary)
RCT_EXPORT_VIEW_PROPERTY(isMuted, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(isVideoEnabled, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(enablePinchedZoom, NSNumber)
RCT_EXPORT_VIEW_PROPERTY(onConnectionSuccess, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onConnectionFailed, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onDisconnect, RCTDirectEventBlock)

@end
