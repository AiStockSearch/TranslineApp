package org.transline.geoworker

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

/**
 * Autolinked RN package: LocationTracking, SystemBars, NotifyApp.
 *
 * [TurboReactPackage] + [ReactModuleInfoProvider] is required so New Arch
 * (bridgeless) can resolve modules via TurboModuleRegistry — classic
 * [createNativeModules] alone leaves [NotifyApp] undefined in JS.
 */
class GeoWorkerPackage : TurboReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return when (name) {
            "LocationTracking" -> LocationTrackerModule(reactContext)
            "SystemBars" -> SystemBarsModule(reactContext)
            "NotifyApp" -> NotifyAppModule(reactContext)
            else -> null
        }
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        return ReactModuleInfoProvider {
            val isTurbo = true
            mapOf(
                "LocationTracking" to ReactModuleInfo(
                    "LocationTracking",
                    LocationTrackerModule::class.java.name,
                    false,
                    false,
                    false,
                    isTurbo,
                ),
                "SystemBars" to ReactModuleInfo(
                    "SystemBars",
                    SystemBarsModule::class.java.name,
                    false,
                    false,
                    false,
                    isTurbo,
                ),
                "NotifyApp" to ReactModuleInfo(
                    "NotifyApp",
                    NotifyAppModule::class.java.name,
                    false,
                    false,
                    false,
                    isTurbo,
                ),
            )
        }
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }
}
