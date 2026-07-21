package org.transline.geoworker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * После reboot / restore — если трекинг был активен, поднимаем FGS.
 * Аналог восстановления из tz.md §4.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val storage = GeoWorkerRuntime.storage(context)
        if (!storage.isTrackingActive()) {
            Log.d(TAG, "Boot: tracking inactive, skip")
            return
        }

        Log.d(TAG, "Boot: resuming LocationForegroundService")
        LocationForegroundService.start(context.applicationContext)
    }

    companion object {
        private const val TAG = "GeoBootReceiver"
    }
}
