declare module 'react-native/Libraries/Types/CodegenTypes' {
  export type Double = number;
  export type Int32 = number;
  export type WithDefault<T, _Default> = T;
  export type DirectEventHandler<T> = (event: { nativeEvent: T }) => void;
}

declare module 'react-native/Libraries/Utilities/codegenNativeComponent' {
  import type { HostComponent } from 'react-native';

  export default function codegenNativeComponent<Props>(
    componentName: string,
    options?: Readonly<{ interfaceOnly?: boolean }>
  ): HostComponent<Props>;
}
