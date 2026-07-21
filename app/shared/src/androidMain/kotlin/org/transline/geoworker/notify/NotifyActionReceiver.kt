package org.transline.geoworker.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles notification action buttons, content tap, and snooze alarm.
 * Register in host AndroidManifest (see docs/15-notify-manager.md).
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        NotifyAndroidContext.init(context)
        val mgr = NotifyManagerHolder.getOrCreate()

        when (intent.action) {
            NotifyIntents.ACTION_SNOOZE_FIRE -> {
                val payload = AndroidPlatformNotifier.payloadFromExtras(intent.extras) ?: return
                mgr.show(payload)
            }
            NotifyIntents.ACTION_BUTTON -> {
                val id = intent.getStringExtra(NotifyIntents.EXTRA_ID) ?: return
                val actionRaw = intent.getStringExtra(NotifyIntents.EXTRA_ACTION) ?: return
                val actionId = NotifyActionId.fromWire(actionRaw) ?: return
                AndroidPlatformNotifier.payloadFromExtras(intent.extras)?.let { mgr.seedCache(it) }
                mgr.dispatchAction(actionId, id)
            }
            NotifyIntents.ACTION_OPEN_CONTENT -> {
                val id = intent.getStringExtra(NotifyIntents.EXTRA_ID) ?: return
                AndroidPlatformNotifier.payloadFromExtras(intent.extras)?.let { mgr.seedCache(it) }
                mgr.dispatchAction(NotifyActionId.OPEN, id)
            }
        }
    }
}
