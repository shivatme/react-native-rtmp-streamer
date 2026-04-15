import { forwardRef, useImperativeHandle, useRef, type Ref } from 'react';
import { findNodeHandle } from 'react-native';
import VideoLiveStreamNativeComponent from './VideoLiveStreamNativeComponent';
import NativeVideoLiveStream from './NativeVideoLiveStream';
import type { VideoLiveStreamMethods, VideoLiveStreamProps } from './types';

function VideoLiveStream(
  {
    camera = 'back',
    isMuted = false,
    isVideoEnabled = true,
    enablePinchedZoom = false,
    onConnectionSuccess,
    onConnectionFailed,
    onDisconnect,
    style,
    video,
    audio,
    onLayout,
  }: VideoLiveStreamProps,
  ref: Ref<VideoLiveStreamMethods>
) {
  const nativeRef = useRef<unknown>(null);

  useImperativeHandle(
    ref,
    (): VideoLiveStreamMethods => ({
      startStreaming(streamKeyOrUrl, url, nextCamera) {
        const viewTag = findNodeHandle(nativeRef.current as never);
        if (viewTag == null) {
          return;
        }

        const trimmedTarget = streamKeyOrUrl.trim();
        const isAbsoluteRtmpTarget =
          trimmedTarget.startsWith('rtmp://') ||
          trimmedTarget.startsWith('rtmps://');

        NativeVideoLiveStream.startStreaming(
          viewTag,
          trimmedTarget,
          isAbsoluteRtmpTarget ? undefined : url,
          nextCamera ?? camera
        );
      },
      stopStreaming() {
        const viewTag = findNodeHandle(nativeRef.current as never);
        if (viewTag == null) {
          return;
        }
        NativeVideoLiveStream.stopStreaming(viewTag);
      },
      setZoomRatio(zoomRatio) {
        const viewTag = findNodeHandle(nativeRef.current as never);
        if (viewTag == null) {
          return;
        }
        NativeVideoLiveStream.setZoomRatio(viewTag, zoomRatio);
      },
      getStreamCapabilities(nextCamera) {
        const viewTag = findNodeHandle(nativeRef.current as never);
        if (viewTag == null) {
          return Promise.reject(
            new Error('VideoLiveStream native view is not mounted')
          );
        }
        return NativeVideoLiveStream.getStreamCapabilities(
          viewTag,
          nextCamera ?? camera
        );
      },
    }),
    [camera]
  );

  return (
    <VideoLiveStreamNativeComponent
      ref={nativeRef as never}
      onLayout={onLayout}
      style={style}
      camera={camera}
      video={{
        fps: video.fps,
        width: video.resolution.width,
        height: video.resolution.height,
        bitrate: video.bitrate,
        gopDuration: video.gopDuration,
      }}
      audio={audio}
      isMuted={isMuted}
      isVideoEnabled={isVideoEnabled}
      enablePinchedZoom={enablePinchedZoom}
      onConnectionSuccess={onConnectionSuccess}
      onConnectionFailed={(event) => {
        onConnectionFailed?.(event.nativeEvent?.code ?? 'UNKNOWN_ERROR');
      }}
      onDisconnect={onDisconnect}
    />
  );
}

export default forwardRef(VideoLiveStream);
