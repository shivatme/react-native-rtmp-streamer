import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  SafeAreaView,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import VideoLiveStream, {
  type CameraPosition,
  type Resolution,
  type VideoLiveStreamMethods,
} from 'react-native-rtmp-streamer';

const DEFAULT_RESOLUTION: Resolution = { width: 1280, height: 720 };
const DEFAULT_STREAM_URL = 'rtmp://your-server/app/stream-key';
const SCREEN_BACKGROUND = '#0b1220';

export default function App() {
  return (
    <SafeAreaProvider>
      <ExampleScreen />
    </SafeAreaProvider>
  );
}

function ExampleScreen() {
  const streamRef = useRef<VideoLiveStreamMethods>(null);
  const insets = useSafeAreaInsets();
  const [streamUrl, setStreamUrl] = useState(DEFAULT_STREAM_URL);
  const [camera, setCamera] = useState<CameraPosition>('back');
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoEnabled, setIsVideoEnabled] = useState(true);
  const [isStreaming, setIsStreaming] = useState(false);
  const [hasPermissions, setHasPermissions] = useState(
    Platform.OS !== 'android'
  );
  const [hasCheckedPermissions, setHasCheckedPermissions] = useState(
    Platform.OS !== 'android'
  );

  const requestPermissions = useCallback(async () => {
    if (Platform.OS !== 'android') {
      setHasPermissions(true);
      setHasCheckedPermissions(true);
      return true;
    }

    const granted = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.CAMERA,
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    ]);

    const cameraGranted =
      granted[PermissionsAndroid.PERMISSIONS.CAMERA] ===
      PermissionsAndroid.RESULTS.GRANTED;
    const microphoneGranted =
      granted[PermissionsAndroid.PERMISSIONS.RECORD_AUDIO] ===
      PermissionsAndroid.RESULTS.GRANTED;

    if (!cameraGranted || !microphoneGranted) {
      setHasPermissions(false);
      setHasCheckedPermissions(true);
      Alert.alert(
        'Permissions required',
        'Camera and microphone access are required to preview and start streaming.'
      );
      return false;
    }

    setHasPermissions(true);
    setHasCheckedPermissions(true);
    return true;
  }, []);

  useEffect(() => {
    const checkPermissions = async () => {
      await requestPermissions();
    };

    checkPermissions().catch(() => undefined);
  }, [requestPermissions]);

  const handleStartStream = useCallback(async () => {
    if (!streamUrl.trim()) {
      Alert.alert('Missing RTMP URL', 'Enter your RTMP URL before starting.');
      return;
    }

    const permissionsGranted = await requestPermissions();
    if (!permissionsGranted) {
      return;
    }

    streamRef.current?.startStreaming(streamUrl.trim());
  }, [requestPermissions, streamUrl]);

  const handleStopStream = useCallback(() => {
    streamRef.current?.stopStreaming();
    setIsStreaming(false);
  }, []);

  return (
    <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
      <StatusBar
        barStyle="light-content"
        backgroundColor={SCREEN_BACKGROUND}
        translucent={false}
      />
      <ScrollView
        contentContainerStyle={[
          styles.content,
          {
            paddingTop: Math.max(12, insets.top > 0 ? 12 : 20),
            paddingBottom: Math.max(32, insets.bottom + 20),
          },
        ]}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.eyebrow}>react-native-rtmp-streamer</Text>
        <Text style={styles.title}>Basic live streaming example</Text>
        <Text style={styles.description}>
          This example mounts the preview, lets you change a few common props,
          and starts or stops an RTMP stream from the imperative ref.
        </Text>

        <View style={styles.previewCard}>
          {hasPermissions ? (
            <VideoLiveStream
              ref={streamRef}
              style={styles.preview}
              camera={camera}
              video={{
                fps: 30,
                resolution: DEFAULT_RESOLUTION,
                bitrate: 2_000_000,
                gopDuration: 2,
              }}
              audio={{
                sampleRate: 44_100,
                isStereo: true,
                bitrate: 128_000,
              }}
              isMuted={isMuted}
              isVideoEnabled={isVideoEnabled}
              enablePinchedZoom
              onConnectionSuccess={() => setIsStreaming(true)}
              onConnectionFailed={(code) => {
                setIsStreaming(false);
                Alert.alert('Connection failed', `Native error: ${code}`);
              }}
              onDisconnect={() => setIsStreaming(false)}
            />
          ) : (
            <View style={[styles.preview, styles.permissionCard]}>
              <Text style={styles.permissionTitle}>
                Camera permission needed
              </Text>
              <Text style={styles.permissionText}>
                Allow camera and microphone access to show the preview and start
                streaming.
              </Text>
              <Pressable
                onPress={() => {
                  requestPermissions().catch(() => undefined);
                }}
                style={styles.permissionButton}
              >
                <Text style={styles.permissionButtonText}>
                  Grant permissions
                </Text>
              </Pressable>
            </View>
          )}
          <View style={styles.previewBadge}>
            <Text style={styles.previewBadgeText}>
              {isStreaming ? 'LIVE' : hasPermissions ? 'PREVIEW' : 'LOCKED'}
            </Text>
          </View>
        </View>

        <View style={styles.section}>
          <Text style={styles.label}>RTMP URL</Text>
          <TextInput
            value={streamUrl}
            onChangeText={setStreamUrl}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="rtmp://your-server/app/stream-key"
            placeholderTextColor="#7b8694"
            style={styles.input}
          />
          <Text style={styles.helperText}>
            You can pass a full RTMP URL directly to{' '}
            {'`startStreaming(streamUrl)`'}.
          </Text>
        </View>

        <View style={styles.section}>
          <Text style={styles.label}>Common controls</Text>
          <View style={styles.controlsRow}>
            <Pressable
              onPress={() =>
                setCamera((current) => (current === 'back' ? 'front' : 'back'))
              }
              style={styles.secondaryButton}
            >
              <Text style={styles.secondaryButtonText}>
                Camera: {camera === 'back' ? 'Back' : 'Front'}
              </Text>
            </Pressable>

            <Pressable
              onPress={() => setIsMuted((current) => !current)}
              style={styles.secondaryButton}
            >
              <Text style={styles.secondaryButtonText}>
                {isMuted ? 'Unmute mic' : 'Mute mic'}
              </Text>
            </Pressable>

            <Pressable
              onPress={() => setIsVideoEnabled((current) => !current)}
              style={styles.secondaryButton}
            >
              <Text style={styles.secondaryButtonText}>
                {isVideoEnabled ? 'Disable camera' : 'Enable camera'}
              </Text>
            </Pressable>
          </View>
        </View>

        <Pressable
          onPress={isStreaming ? handleStopStream : handleStartStream}
          disabled={!hasCheckedPermissions && Platform.OS === 'android'}
          style={[
            styles.primaryButton,
            isStreaming && styles.primaryButtonStop,
            !hasCheckedPermissions &&
              Platform.OS === 'android' &&
              styles.primaryButtonDisabled,
          ]}
        >
          <Text style={styles.primaryButtonText}>
            {isStreaming
              ? 'Stop stream'
              : hasCheckedPermissions || Platform.OS !== 'android'
                ? 'Start stream'
                : 'Checking permissions...'}
          </Text>
        </Pressable>

        <View style={styles.codeBlock}>
          <Text style={styles.codeTitle}>What this shows</Text>
          <Text style={styles.codeLine}>
            1. Render `&lt;VideoLiveStream /&gt;` with video and audio config.
          </Text>
          <Text style={styles.codeLine}>
            2. Keep a ref to call `startStreaming()` and `stopStreaming()`.
          </Text>
          <Text style={styles.codeLine}>
            3. Drive camera, mute, and video state through props.
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: SCREEN_BACKGROUND,
  },
  content: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 32,
    gap: 16,
  },
  eyebrow: {
    color: '#7dd3fc',
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.8,
    textTransform: 'uppercase',
  },
  title: {
    color: '#f8fafc',
    fontSize: 28,
    fontWeight: '800',
  },
  description: {
    color: '#cbd5e1',
    fontSize: 15,
    lineHeight: 22,
  },
  previewCard: {
    overflow: 'hidden',
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#1e293b',
    backgroundColor: '#020617',
  },
  preview: {
    width: '100%',
    aspectRatio: 9 / 16,
    backgroundColor: '#111827',
  },
  permissionCard: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    gap: 12,
  },
  permissionTitle: {
    color: '#f8fafc',
    fontSize: 20,
    fontWeight: '700',
    textAlign: 'center',
  },
  permissionText: {
    color: '#cbd5e1',
    fontSize: 14,
    lineHeight: 21,
    textAlign: 'center',
  },
  permissionButton: {
    borderRadius: 12,
    backgroundColor: '#0284c7',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  permissionButtonText: {
    color: '#f8fafc',
    fontSize: 14,
    fontWeight: '700',
  },
  previewBadge: {
    position: 'absolute',
    top: 12,
    right: 12,
    borderRadius: 999,
    backgroundColor: 'rgba(15, 23, 42, 0.82)',
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  previewBadgeText: {
    color: '#f8fafc',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  section: {
    gap: 8,
  },
  label: {
    color: '#f8fafc',
    fontSize: 16,
    fontWeight: '700',
  },
  input: {
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#334155',
    backgroundColor: '#111827',
    color: '#f8fafc',
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 15,
  },
  helperText: {
    color: '#94a3b8',
    fontSize: 13,
    lineHeight: 18,
  },
  controlsRow: {
    gap: 10,
  },
  secondaryButton: {
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#334155',
    backgroundColor: '#111827',
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  secondaryButtonText: {
    color: '#e2e8f0',
    fontSize: 15,
    fontWeight: '600',
  },
  primaryButton: {
    alignItems: 'center',
    borderRadius: 16,
    backgroundColor: '#0284c7',
    paddingVertical: 16,
  },
  primaryButtonStop: {
    backgroundColor: '#dc2626',
  },
  primaryButtonDisabled: {
    backgroundColor: '#475569',
  },
  primaryButtonText: {
    color: '#f8fafc',
    fontSize: 16,
    fontWeight: '800',
  },
  codeBlock: {
    gap: 8,
    borderRadius: 18,
    backgroundColor: '#111827',
    padding: 16,
  },
  codeTitle: {
    color: '#f8fafc',
    fontSize: 15,
    fontWeight: '700',
  },
  codeLine: {
    color: '#cbd5e1',
    fontSize: 14,
    lineHeight: 20,
  },
});
