package org.transline.geoworker.notify

/**
 * Action ids for notification buttons (max [NotifyManager.MAX_ACTIONS] per payload).
 */
enum class NotifyActionId {
    READ,
    OPEN,
    CLOSE,
    SNOOZE,
    ;

    companion object {
        fun fromWire(raw: String): NotifyActionId? {
            return when (raw.trim().lowercase()) {
                "read", "прочитать" -> READ
                "open", "go", "navigate", "перейти" -> OPEN
                "close", "dismiss", "закрыть" -> CLOSE
                "snooze", "отложить" -> SNOOZE
                else -> entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            }
        }
    }
}

/**
 * Button on a notification. Optional [deepLink] / [route] / [params] override payload-level link.
 */
data class NotifyAction(
    val id: NotifyActionId,
    val title: String,
    val deepLink: String? = null,
    val route: String? = null,
    val params: Map<String, String> = emptyMap(),
)

data class NotifyPayload(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String? = null,
    val deepLink: String? = null,
    val channelId: String? = null,
    val actions: List<NotifyAction> = emptyList(),
    val data: Map<String, String> = emptyMap(),
    val snoozeMinutes: Int? = null,
    /** Explicit aggregation key (route hub). If null, derived from deepLink. */
    val groupKey: String? = null,
    /** Entity id within [groupKey]. If null, derived from deepLink last segment. */
    val entityId: String? = null,
)

fun interface NotifyActionListener {
    fun onNotifyAction(actionId: NotifyActionId, payload: NotifyPayload)
}

interface NotifyEventListener {
    fun onShown(payload: NotifyPayload) {}
    fun onCancelled(id: String) {}
    fun onAction(actionId: NotifyActionId, payload: NotifyPayload) {}
}

/** Wire event type strings for RN / host. */
object NotifyEventType {
    const val SHOWN = "SHOWN"
    const val CANCELLED = "CANCELLED"
    const val ACTION_READ = "ACTION_READ"
    const val ACTION_OPEN = "ACTION_OPEN"
    const val ACTION_CLOSE = "ACTION_CLOSE"
    const val ACTION_SNOOZE = "ACTION_SNOOZE"

    fun forAction(id: NotifyActionId): String = when (id) {
        NotifyActionId.READ -> ACTION_READ
        NotifyActionId.OPEN -> ACTION_OPEN
        NotifyActionId.CLOSE -> ACTION_CLOSE
        NotifyActionId.SNOOZE -> ACTION_SNOOZE
    }
}

/** Resolve navigation target for a tapped action (action fields win over payload). */
object NotifyActionNav {
    fun actionOf(actionId: NotifyActionId, payload: NotifyPayload): NotifyAction? =
        payload.actions.firstOrNull { it.id == actionId }

    fun deepLink(actionId: NotifyActionId, payload: NotifyPayload): String? {
        val action = actionOf(actionId, payload)
        return action?.deepLink?.takeIf { it.isNotBlank() }
            ?: payload.deepLink?.takeIf { it.isNotBlank() }
    }

    fun route(actionId: NotifyActionId, payload: NotifyPayload): String? {
        val action = actionOf(actionId, payload)
        return action?.route?.takeIf { it.isNotBlank() }
    }

    fun params(actionId: NotifyActionId, payload: NotifyPayload): Map<String, String> {
        return actionOf(actionId, payload)?.params ?: emptyMap()
    }
}
