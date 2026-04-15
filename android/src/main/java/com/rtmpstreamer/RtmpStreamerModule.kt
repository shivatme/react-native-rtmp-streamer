package com.rtmpstreamer

import com.facebook.react.bridge.ReactApplicationContext

class RtmpStreamerModule(reactContext: ReactApplicationContext) :
  NativeRtmpStreamerSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeRtmpStreamerSpec.NAME
  }
}
