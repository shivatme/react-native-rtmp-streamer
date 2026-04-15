package com.rtmpstreamer.livestream

import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.UIManagerHelper

@ReactModule(name = VideoLiveStreamModule.NAME)
class VideoLiveStreamModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  @ReactMethod
  fun startStreaming(viewTag: Double, streamKey: String, url: String?, camera: String?) {
    withView(viewTag) { view ->
      view.startStreaming(streamKey, url, camera)
    }
  }

  @ReactMethod
  fun stopStreaming(viewTag: Double) {
    withView(viewTag) { view ->
      view.stopStreaming()
    }
  }

  @ReactMethod
  fun setZoomRatio(viewTag: Double, zoomRatio: Double) {
    withView(viewTag) { view ->
      view.setZoomRatio(zoomRatio)
    }
  }

  @ReactMethod
  fun getStreamCapabilities(viewTag: Double, camera: String?, promise: Promise) {
    UiThreadUtil.runOnUiThread {
      val resolvedViewTag = viewTag.toInt()
      val uiManager = UIManagerHelper.getUIManagerForReactTag(reactApplicationContext, resolvedViewTag)
      val view = uiManager?.resolveView(resolvedViewTag) as? VideoLiveStreamView

      if (view == null) {
        promise.reject(NAME, "Unable to resolve VideoLiveStreamView for tag $resolvedViewTag")
        return@runOnUiThread
      }

      promise.resolve(view.getStreamCapabilities(camera))
    }
  }

  private fun withView(viewTag: Double, action: (VideoLiveStreamView) -> Unit) {
    UiThreadUtil.runOnUiThread {
      val resolvedViewTag = viewTag.toInt()
      val uiManager = UIManagerHelper.getUIManagerForReactTag(reactApplicationContext, resolvedViewTag)
      val view = uiManager?.resolveView(resolvedViewTag) as? VideoLiveStreamView

      if (view == null) {
        Log.w(NAME, "Unable to resolve VideoLiveStreamView for tag $resolvedViewTag")
        return@runOnUiThread
      }

      action(view)
    }
  }

  companion object {
    const val NAME = "VideoLiveStreamModule"
  }
}
