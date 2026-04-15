package com.rtmpstreamer.livestream

import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.ModuleSpec
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
open class VideoLiveStreamPackage : BaseReactPackage() {

  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
      listOf(VideoLiveStreamModule(reactContext))

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      when (name) {
        VideoLiveStreamModule.NAME -> VideoLiveStreamModule(reactContext)
        else -> null
      }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
      ReactModuleInfoProvider {
        mapOf(
            VideoLiveStreamModule.NAME to
                ReactModuleInfo(
                    VideoLiveStreamModule.NAME,
                    VideoLiveStreamModule::class.java.name,
                    false,
                    false,
                    false,
                    ReactModuleInfo.classIsTurboModule(VideoLiveStreamModule::class.java),
                ),
        )
      }

  override fun createViewManagers(
      reactContext: ReactApplicationContext
  ): List<ViewManager<in Nothing, in Nothing>> = listOf(VideoLiveStreamViewManager())

  override fun getViewManagers(reactContext: ReactApplicationContext): List<ModuleSpec> =
      listOf(ModuleSpec.viewManagerSpec { VideoLiveStreamViewManager() })
}
