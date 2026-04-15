import { NativeModules } from 'react-native';

export type NativeResolution = Readonly<{
  width: number;
  height: number;
  maxFps: number;
}>;

export type NativeStreamCapabilities = Readonly<{
  performanceClass: 'low' | 'medium' | 'high';
  resolutions: readonly NativeResolution[];
}>;

type VideoLiveStreamNativeModule = Readonly<{
  startStreaming: (
    viewTag: number,
    streamKey: string,
    url?: string,
    camera?: 'front' | 'back'
  ) => void;
  stopStreaming: (viewTag: number) => void;
  setZoomRatio: (viewTag: number, zoomRatio: number) => void;
  getStreamCapabilities: (
    viewTag: number,
    camera?: 'front' | 'back'
  ) => Promise<NativeStreamCapabilities>;
}>;

const maybeNativeModule = NativeModules.VideoLiveStreamModule as
  | VideoLiveStreamNativeModule
  | undefined;

if (maybeNativeModule == null) {
  throw new Error('VideoLiveStreamModule is not available');
}

const nativeModule: VideoLiveStreamNativeModule = maybeNativeModule;

export default nativeModule;
