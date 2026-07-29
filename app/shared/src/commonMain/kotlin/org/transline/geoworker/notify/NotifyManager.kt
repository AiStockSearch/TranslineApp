package org.transline.geoworker.notify

import kotlin.concurrent.Volatile

/**
 * KMP Notify App Manager — show / handleRemote / cancel / snooze.
 * Display is delegated to [PlatformNotifier]; geo FGS notifications stay separate.
 */
class NotifyManager(
    private val notifier: PlatformNotifier = createPlatformNotifier(),
) {
    companion object {
        const val MAX_ACTIONS = 3
        const val DEFAULT_SNOOZE_MINUTES = 15
        const val DEFAULT_CHANNEL_ID = "notify_app_channel"

        /** Ownership marker in userInfo / FCM data / Android extras (coexistence with Firebase). */
        const val OWNER_MARKER_KEY = "tl_notify"
        const val OWNER_MARKER_VALUE = "1"

        /** Recommended FCM/APNs `data.type` for Notify (JS router). */
        const val REMOTE_TYPE = "notify_app"

        const val CATEGORY_ID_PREFIX = "notify_app_"

        /** Max entity ids kept per route [groupKey] in the shade summary. */
        const val MAX_GROUP_IDS = 15
    }

    private val cache = mutableMapOf<String, NotifyPayload>()
    private val groupStore = NotifyGroupStore()
    private val eventListeners = mutableListOf<NotifyEventListener>()
    private var actionListener: NotifyActionListener? = null

    fun addEventListener(listener: NotifyEventListener) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: NotifyEventListener) {
        eventListeners.remove(listener)
    }

    fun setActionListener(listener: NotifyActionListener?) {
        actionListener = listener
    }

    /**
     * Normalize and show. Returns false if id/title invalid.
     * When [NotifyPayload.groupKey]/[NotifyPayload.entityId] (or deepLink) resolve to a group,
     * shows/updates a single summary notification for the route hub.
     */
    fun show(payload: NotifyPayload): Boolean {
        val normalized = normalize(payload) ?: return false
        val aggregated = groupStore.add(normalized)
        val toShow = aggregated ?: normalized
        cache[toShow.id] = toShow
        if (aggregated != null) {
            // Keep entity id cached for cancel-by-entity
            cache[normalized.id] = normalized
        }
        val ok = notifier.show(toShow)
        if (ok) {
            eventListeners.forEach { it.onShown(toShow) }
        }
        return ok
    }

    /**
     * Parse backend/FCM data map and show.
     */
    fun handleRemote(data: Map<String, String>): Boolean {
        val payload = NotifyRemoteParser.parse(data) ?: return false
        return show(payload)
    }

    fun cancel(id: String): Boolean {
        if (id.isBlank()) return false

        // Summary notification id (grp:…)
        NotifyGroupStore.groupKeyFromSummaryId(id)?.let { groupKey ->
            groupStore.clear(groupKey)
            cache.remove(id)
            notifier.cancel(id)
            eventListeners.forEach { it.onCancelled(id) }
            return true
        }

        // Cancel by groupKey / hub path
        if (groupStore.get(id) != null) {
            val summaryId = NotifyGroupStore.summaryNotificationId(id)
            groupStore.clear(id)
            cache.remove(summaryId)
            notifier.cancel(summaryId)
            eventListeners.forEach { it.onCancelled(summaryId) }
            return true
        }

        // Entity id inside an aggregated group
        val groupKey = groupStore.findGroupKeyForEntity(id)
        if (groupKey != null) {
            val updated = groupStore.removeEntity(id)
            cache.remove(id)
            if (updated != null) {
                cache[updated.id] = updated
                notifier.show(updated)
            } else {
                val summaryId = NotifyGroupStore.summaryNotificationId(groupKey)
                cache.remove(summaryId)
                notifier.cancel(summaryId)
            }
            eventListeners.forEach { it.onCancelled(id) }
            return true
        }

        cache.remove(id)
        notifier.cancel(id)
        eventListeners.forEach { it.onCancelled(id) }
        return true
    }

    /**
     * Cancel current notification and schedule the same payload again after [minutes].
     */
    fun snooze(id: String, minutes: Int? = null): Boolean {
        val payload = cache[id] ?: return false
        val delay = (minutes ?: payload.snoozeMinutes ?: DEFAULT_SNOOZE_MINUTES).coerceAtLeast(1)
        notifier.cancel(id)
        eventListeners.forEach { it.onCancelled(id) }
        notifier.scheduleSnooze(payload, delay)
        return true
    }

    /**
     * Called from platform action PendingIntent / UNNotification response.
     */
    fun dispatchAction(actionId: NotifyActionId, notificationId: String) {
        val payload = cache[notificationId] ?: NotifyPayload(
            id = notificationId,
            title = "",
            body = "",
        )
        when (actionId) {
            NotifyActionId.CLOSE -> {
                cancel(notificationId)
            }
            NotifyActionId.SNOOZE -> {
                snooze(notificationId, payload.snoozeMinutes)
            }
            NotifyActionId.READ, NotifyActionId.OPEN -> {
                // Keep in shade unless host cancels; still emit event
            }
        }
        actionListener?.onNotifyAction(actionId, payload)
        eventListeners.forEach { it.onAction(actionId, payload) }
    }

    fun getCached(id: String): NotifyPayload? = cache[id]

    /** Restore payload into cache after process death (no platform show). */
    fun seedCache(payload: NotifyPayload) {
        normalize(payload)?.let { cache[it.id] = it }
    }

    fun normalize(payload: NotifyPayload): NotifyPayload? {
        val id = payload.id.trim()
        val title = payload.title.trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val actions = payload.actions.take(MAX_ACTIONS).map { action ->
            val titleText = action.title.ifBlank { NotifyRemoteParser.defaultTitle(action.id) }
            NotifyAction(
                id = action.id,
                title = titleText,
                deepLink = action.deepLink?.takeIf { it.isNotBlank() },
                route = action.route?.takeIf { it.isNotBlank() },
                params = action.params,
            )
        }
        return payload.copy(
            id = id,
            title = title,
            body = payload.body,
            channelId = payload.channelId?.takeIf { it.isNotBlank() } ?: DEFAULT_CHANNEL_ID,
            actions = actions,
            groupKey = payload.groupKey?.takeIf { it.isNotBlank() },
            entityId = payload.entityId?.takeIf { it.isNotBlank() },
        )
    }
}
/** Process-wide holder so Android BroadcastReceiver / iOS delegate can reach the manager. */
object NotifyManagerHolder {
    @Volatile
    var instance: NotifyManager? = null

    fun getOrCreate(): NotifyManager {
        instance?.let { return it }
        return NotifyManager().also { instance = it }
    }
}
