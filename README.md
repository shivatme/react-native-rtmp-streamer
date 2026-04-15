# react-native-rtmp-streamer

React Native module for RTMP live streaming using native iOS (HaishinKit) and Android (RootEncoder)

## Installation

```sh
npm install react-native-rtmp-streamer
```

## Usage

```tsx
import { useRef } from 'react';
import VideoLiveStream, {
  type Resolution,
  type VideoLiveStreamMethods,
} from 'react-native-rtmp-streamer';

const defaultResolution: Resolution = { width: 1280, height: 720 };
const streamRef = useRef<VideoLiveStreamMethods | null>(null);

export default function App() {
  return (
    <VideoLiveStream
      style={{ flex: 1 }}
      camera="back"
      video={{
        fps: 30,
        width: defaultResolution.width,
        height: defaultResolution.height,
        bitrate: 1_500_000,
        gopDuration: 2,
      }}
      audio={{
        sampleRate: 44100,
        isStereo: true,
        bitrate: 128000,
      }}
    />
  );
}
```

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
