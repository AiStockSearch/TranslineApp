package org.transline.geoworker.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

actual fun createPlatformNotifier(): PlatformNotifier = AndroidPlatformNotifier()

class AndroidPlatformNotifier : PlatformNotifier {

    private val executor = Executors.newSingleThreadExecutor()

    override fun show(payload: NotifyPayload): Boolean {
        val context = NotifyAndroidContext.getOrNull() ?: return false
        ensureChannel(context, payload.channelId ?: NotifyManager.DEFAULT_CHANNEL_ID)

        val groupKey = payload.groupKey?.takeIf { it.isNotBlank() }
            ?: payload.data["groupKey"]?.takeIf { it.isNotBlank() }
        val isSummary = NotifyGroupStore.isSummaryId(payload.id) ||
            (groupKey != null && payload.data.containsKey("ids"))

        val builder = NotificationCompat.Builder(context, payload.channelId ?: NotifyManager.DEFAULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (isSummary && groupKey != null) {
            builder.setGroup(groupKey)
            builder.setGroupSummary(true)
            val ids = payload.data["ids"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val inbox = NotificationCompat.InboxStyle()
                .setSummaryText(payload.body)
            ids.take(7).forEach { inbox.addLine(it) }
            builder.setStyle(inbox)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }

        val openLink = payload.deepLink
        if (!openLink.isNullOrBlank()) {
            val open = Intent(context, NotifyActionReceiver::class.java).apply {
                action = NotifyIntents.ACTION_OPEN_CONTENT
                putExtra(NotifyIntents.EXTRA_ID, payload.id)
                putExtra(NotifyIntents.EXTRA_DEEP_LINK, openLink)
                putExtras(payloadToExtras(payload))
            }
            val pi = PendingIntent.getBroadcast(
                context,
                stableNotifyId(payload.id),
                open,
                pendingFlags(),
            )
            builder.setContentIntent(pi)
        }

        payload.actions.forEachIndexed { index, notifyAction ->
            val buttonIntent = Intent(context, NotifyActionReceiver::class.java).apply {
                this.action = NotifyIntents.ACTION_BUTTON
                putExtra(NotifyIntents.EXTRA_ID, payload.id)
                putExtra(NotifyIntents.EXTRA_ACTION, notifyAction.id.name)
                putExtras(payloadToExtras(payload))
            }
            val pi = PendingIntent.getBroadcast(
                context,
                stableNotifyId(payload.id) * 31 + index + 1,
                buttonIntent,
                pendingFlags(),
            )
            builder.addAction(0, notifyAction.title, pi)
        }

        val imageUrl = payload.imageUrl
        if (!imageUrl.isNullOrBlank() && !isSummary) {
            executor.execute {
                val bitmap = downloadBitmap(imageUrl)
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .setSummaryText(payload.body)
                    )
                    builder.setLargeIcon(bitmap)
                }
                notifyNow(context, payload.id, builder.build())
            }
        } else {
            notifyNow(context, payload.id, builder.build())
        }
        return true
    }

    override fun cancel(id: String) {
        val context = NotifyAndroidContext.getOrNull() ?: return
        NotificationManagerCompat.from(context).cancel(stableNotifyId(id))
        cancelSnoozeAlarm(context, id)
    }

    override fun scheduleSnooze(payload: NotifyPayload, delayMinutes: Int) {
        val context = NotifyAndroidContext.getOrNull() ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotifyActionReceiver::class.java).apply {
            action = NotifyIntents.ACTION_SNOOZE_FIRE
            putExtras(payloadToExtras(payload))
        }
        val pi = PendingIntent.getBroadcast(
            context,
            stableNotifyId(payload.id) + 10_000,
            intent,
            pendingFlags(),
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMinutes * 60_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelSnoozeAlarm(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotifyActionReceiver::class.java).apply {
            action = NotifyIntents.ACTION_SNOOZE_FIRE
            putExtra(NotifyIntents.EXTRA_ID, id)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            stableNotifyId(id) + 10_000,
            intent,
            pendingFlags() or PendingIntent.FLAG_NO_CREATE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun notifyNow(context: Context, id: String, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(stableNotifyId(id), notification)
    }

    private fun ensureChannel(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(
            channelId,
            "Notify App",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    private fun pendingFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                doInput = true
            }
            conn.connect()
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    companion object {
        fun stableNotifyId(id: String): Int = "tl_notify:$id".hashCode() and 0x7FFFFFFF

        fun payloadToExtras(payload: NotifyPayload): android.os.Bundle {
            return android.os.Bundle().apply {
                putString(NotifyIntents.EXTRA_TL_NOTIFY, NotifyManager.OWNER_MARKER_VALUE)
                putString(NotifyIntents.EXTRA_ID, payload.id)
                putString(NotifyIntents.EXTRA_TITLE, payload.title)
                putString(NotifyIntents.EXTRA_BODY, payload.body)
                putString(NotifyIntents.EXTRA_IMAGE_URL, payload.imageUrl)
                putString(NotifyIntents.EXTRA_DEEP_LINK, payload.deepLink)
                putString(NotifyIntents.EXTRA_CHANNEL_ID, payload.channelId)
                putString(NotifyIntents.EXTRA_GROUP_KEY, payload.groupKey)
                putString(NotifyIntents.EXTRA_ENTITY_ID, payload.entityId)
                putInt(
                    NotifyIntents.EXTRA_SNOOZE_MINUTES,
                    payload.snoozeMinutes ?: NotifyManager.DEFAULT_SNOOZE_MINUTES,
                )
                putString(NotifyIntents.EXTRA_ACTIONS_JSON, NotifyRemoteParser.actionsToJson(payload.actions))
                payload.data.forEach { (k, v) -> putString("data_$k", v) }
            }
        }

        fun payloadFromExtras(extras: android.os.Bundle?): NotifyPayload? {
            if (extras == null) return null
            val id = extras.getString(NotifyIntents.EXTRA_ID) ?: return null
            val title = extras.getString(NotifyIntents.EXTRA_TITLE) ?: return null
            val body = extras.getString(NotifyIntents.EXTRA_BODY).orEmpty()
            val data = mutableMapOf<String, String>()
            extras.keySet().forEach { key ->
                if (key.startsWith("data_")) {
                    extras.getString(key)?.let { data[key.removePrefix("data_")] = it }
                }
            }
            return NotifyPayload(
                id = id,
                title = title,
                body = body,
                imageUrl = extras.getString(NotifyIntents.EXTRA_IMAGE_URL),
                deepLink = extras.getString(NotifyIntents.EXTRA_DEEP_LINK),
                channelId = extras.getString(NotifyIntents.EXTRA_CHANNEL_ID),
                actions = NotifyRemoteParser.parseActions(extras.getString(NotifyIntents.EXTRA_ACTIONS_JSON)),
                data = data,
                snoozeMinutes = extras.getInt(
                    NotifyIntents.EXTRA_SNOOZE_MINUTES,
                    NotifyManager.DEFAULT_SNOOZE_MINUTES,
                ),
                groupKey = extras.getString(NotifyIntents.EXTRA_GROUP_KEY),
                entityId = extras.getString(NotifyIntents.EXTRA_ENTITY_ID),
            )
        }
    }
}

object NotifyIntents {
    const val ACTION_BUTTON = "org.transline.geoworker.notify.ACTION_BUTTON"
    const val ACTION_OPEN_CONTENT = "org.transline.geoworker.notify.ACTION_OPEN"
    const val ACTION_SNOOZE_FIRE = "org.transline.geoworker.notify.ACTION_SNOOZE_FIRE"

    const val EXTRA_TL_NOTIFY = NotifyManager.OWNER_MARKER_KEY
    const val EXTRA_ID = "notify_id"
    const val EXTRA_ACTION = "notify_action"
    const val EXTRA_TITLE = "notify_title"
    const val EXTRA_BODY = "notify_body"
    const val EXTRA_IMAGE_URL = "notify_image_url"
    const val EXTRA_DEEP_LINK = "notify_deep_link"
    const val EXTRA_CHANNEL_ID = "notify_channel_id"
    const val EXTRA_SNOOZE_MINUTES = "notify_snooze_minutes"
    const val EXTRA_ACTIONS_JSON = "notify_actions_json"
    const val EXTRA_GROUP_KEY = "notify_group_key"
    const val EXTRA_ENTITY_ID = "notify_entity_id"
}
