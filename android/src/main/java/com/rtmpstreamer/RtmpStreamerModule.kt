package com.rtmpstreamer

import com.facebook.react.bridge.*
import com.pedro.rtplibrary.rtmp.RtmpCamera1
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import android.view.SurfaceView

class RtmpStreamerModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext), ConnectCheckerRtmp {

  private var rtmpCamera: RtmpCamera1? = null

  override fun getName(): String {
    return "RtmpStreamer"
  }

  @ReactMethod
  fun startStream(url: String) {
    val activity = reactApplicationContext.currentActivity ?: return
    val surfaceView = SurfaceView(activity)

    rtmpCamera = RtmpCamera1(surfaceView, this)

    if (rtmpCamera!!.prepareAudio() && rtmpCamera!!.prepareVideo()) {
      rtmpCamera!!.startStream(url)
    }
  }

  @ReactMethod
  fun stopStream() {
    rtmpCamera?.stopStream()
  }

override fun onConnectionStartedRtmp(rtmpUrl: String) {}

override fun onConnectionSuccessRtmp() {}

override fun onConnectionFailedRtmp(reason: String) {}

override fun onNewBitrateRtmp(bitrate: Long) {}

override fun onDisconnectRtmp() {}

override fun onAuthErrorRtmp() {}

override fun onAuthSuccessRtmp() {}
}