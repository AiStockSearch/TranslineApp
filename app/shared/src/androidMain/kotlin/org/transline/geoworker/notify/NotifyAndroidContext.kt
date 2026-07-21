package org.transline.geoworker.notify

import android.content.Context
import kotlin.concurrent.Volatile

/**
 * Must be initialized from Application / RN module before [createPlatformNotifier].
 */
object NotifyAndroidContext {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context {
        return appContext
            ?: error("NotifyAndroidContext.init(context) must be called before Notify Manager on Android")
    }

    fun getOrNull(): Context? = appContext
}
