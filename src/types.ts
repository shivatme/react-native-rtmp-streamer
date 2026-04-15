import type { StyleProp, ViewStyle } from 'react-native';
import type { NativeStreamCapabilities } from './NativeVideoLiveStream';

export type CameraPosition = 'front' | 'back';

export type Resolution = Readonly<{
  width: number;
  height: number;
}>;

export type VideoConfig = Readonly<{
  fps: number;
  resolution: Resolution;
  bitrate: number;
  gopDuration: number;
}>;

export type AudioConfig = Readonly<{
  sampleRate: number;
  isStereo: boolean;
  bitrate: number;
}>;

export type VideoLiveStreamMethods = Readonly<{
  startStreaming: (
    streamKeyOrUrl: string,
    url?: string,
    camera?: CameraPosition
  ) => void;
  stopStreaming: () => void;
  setZoomRatio: (zoomRatio: number) => void;
  getStreamCapabilities: (
    camera?: CameraPosition
  ) => Promise<NativeStreamCapabilities>;
}>;

export type VideoLiveStreamProps = Readonly<{
  style?: StyleProp<ViewStyle>;
  camera?: CameraPosition;
  video: VideoConfig;
  audio: AudioConfig;
  isMuted?: boolean;
  isVideoEnabled?: boolean;
  enablePinchedZoom?: boolean;
  onConnectionSuccess?: () => void;
  onConnectionFailed?: (code: string) => void;
  onDisconnect?: () => void;
  onLayout?: () => void;
}>;

export type { NativeStreamCapabilities };
