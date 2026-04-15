import type { HostComponent, ViewProps } from 'react-native';
import type {
  Double,
  DirectEventHandler,
  Int32,
  WithDefault,
} from 'react-native/Libraries/Types/CodegenTypes';
import { codegenNativeComponent } from 'react-native';

export type NativeVideoConfig = Readonly<{
  fps: Int32;
  width: Int32;
  height: Int32;
  bitrate: Double;
  gopDuration: Double;
}>;

export type NativeAudioConfig = Readonly<{
  sampleRate: Int32;
  isStereo: boolean;
  bitrate: Double;
}>;

export interface NativeProps extends ViewProps {
  camera?: WithDefault<'front' | 'back', 'back'>;
  video: NativeVideoConfig;
  audio: NativeAudioConfig;
  isMuted?: WithDefault<boolean, false>;
  isVideoEnabled?: WithDefault<boolean, true>;
  enablePinchedZoom?: WithDefault<boolean, false>;
  onConnectionSuccess?: DirectEventHandler<null>;
  onConnectionFailed?: DirectEventHandler<
    Readonly<{
      code: string;
    }>
  >;
  onDisconnect?: DirectEventHandler<null>;
}

export default codegenNativeComponent<NativeProps>('VideoLiveStreamView', {
  interfaceOnly: true,
}) as HostComponent<NativeProps>;
