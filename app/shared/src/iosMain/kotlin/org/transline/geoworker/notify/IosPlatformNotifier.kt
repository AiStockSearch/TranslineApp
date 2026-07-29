package org.transline.geoworker.notify

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationAttachment
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionNone
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

actual fun createPlatformNotifier(): PlatformNotifier = IosPlatformNotifier()

@OptIn(ExperimentalForeignApi::class)
class IosPlatformNotifier : PlatformNotifier {

    init {
        NotifyIosDelegateInstaller.installIfNeeded()
    }

    override fun show(payload: NotifyPayload): Boolean {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { _, _ -> }

        registerCategory(payload)

        val content = UNMutableNotificationContent()
        content.setTitle(payload.title)
        content.setBody(payload.body)
        content.setSound(UNNotificationSound.defaultSound)
        content.setCategoryIdentifier(categoryId(payload))
        content.setUserInfo(payloadToUserInfo(payload))

        payload.imageUrl?.takeIf { it.isNotBlank() }?.let { attachImage(content, it) }

        val request = UNNotificationRequest.requestWithIdentifier(
            payload.id,
            content,
            null,
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
        return true
    }

    override fun cancel(id: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id, snoozeRequestId(id)))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id, snoozeRequestId(id)))
    }

    override fun scheduleSnooze(payload: NotifyPayload, delayMinutes: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        registerCategory(payload)

        val content = UNMutableNotificationContent()
        content.setTitle(payload.title)
        content.setBody(payload.body)
        content.setSound(UNNotificationSound.defaultSound)
        content.setCategoryIdentifier(categoryId(payload))
        content.setUserInfo(payloadToUserInfo(payload))
        payload.imageUrl?.takeIf { it.isNotBlank() }?.let { attachImage(content, it) }

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            delayMinutes * 60.0,
            repeats = false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            snoozeRequestId(payload.id),
            content,
            trigger,
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    private fun registerCategory(payload: NotifyPayload) {
        if (payload.actions.isEmpty()) return
        val actions = payload.actions.map { action ->
            val options = when (action.id) {
                NotifyActionId.OPEN, NotifyActionId.READ -> UNNotificationActionOptionForeground
                else -> UNNotificationActionOptionNone
            }
            UNNotificationAction.actionWithIdentifier(
                action.id.name,
                action.title,
                options,
            )
        }
        val category = UNNotificationCategory.categoryWithIdentifier(
            categoryId(payload),
            actions,
            emptyList<Any>(),
            UNNotificationCategoryOptionNone,
        )
        registeredCategories[categoryId(payload)] = category
        mergeAndSetCategories()
    }

    /**
     * Merge Notify categories into the existing set so Firebase/host categories are not wiped.
     */
    private fun mergeAndSetCategories() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationCategoriesWithCompletionHandler { existing ->
            val merged = linkedMapOf<String, UNNotificationCategory>()
            existing?.forEach { item ->
                val cat = item as? UNNotificationCategory ?: return@forEach
                merged[cat.identifier] = cat
            }
            registeredCategories.forEach { (id, cat) -> merged[id] = cat }
            center.setNotificationCategories(merged.values.toSet())
        }
    }

    private fun attachImage(content: UNMutableNotificationContent, imageUrl: String) {
        val remote = NSURL.URLWithString(imageUrl) ?: return
        val data = NSData.dataWithContentsOfURL(remote) ?: return
        val path = NSTemporaryDirectory() + "notify_${NSUUID().UUIDString}.jpg"
        if (!data.writeToFile(path, atomically = true)) return
        val fileUrl = NSURL.fileURLWithPath(path)
        runCatching {
            val errorPtr = null
            val attachment = UNNotificationAttachment.attachmentWithIdentifier(
                "image",
                fileUrl,
                null,
                errorPtr,
            )
            if (attachment != null) {
                content.setAttachments(listOf(attachment))
            }
        }
    }

    private fun categoryId(payload: NotifyPayload): String {
        val actionKey = payload.actions.joinToString("_") { it.id.name }
        return "${NotifyManager.CATEGORY_ID_PREFIX}${actionKey.ifEmpty { "none" }}"
    }

    private fun snoozeRequestId(id: String): String = "snooze_$id"

    companion object {
        private val registeredCategories = mutableMapOf<String, UNNotificationCategory>()

        fun payloadToUserInfo(payload: NotifyPayload): Map<Any?, *> {
            return mapOf(
                NotifyManager.OWNER_MARKER_KEY to NotifyManager.OWNER_MARKER_VALUE,
                "id" to payload.id,
                "title" to payload.title,
                "body" to payload.body,
                "imageUrl" to (payload.imageUrl ?: ""),
                "deepLink" to (payload.deepLink ?: ""),
                "channelId" to (payload.channelId ?: ""),
                "groupKey" to (payload.groupKey ?: ""),
                "entityId" to (payload.entityId ?: ""),
                "snoozeMinutes" to (payload.snoozeMinutes ?: NotifyManager.DEFAULT_SNOOZE_MINUTES).toString(),
                "actions" to NotifyRemoteParser.actionsToJson(payload.actions),
            ) + payload.data.mapKeys { "data_${it.key}" }
        }

        /**
         * Ownership for coexistence: explicit [NotifyManager.OWNER_MARKER_KEY],
         * or category id prefix [NotifyManager.CATEGORY_ID_PREFIX].
         */
        fun isNotifyOwned(info: Map<Any?, *>?, categoryIdentifier: String? = null): Boolean {
            val marker = info?.get(NotifyManager.OWNER_MARKER_KEY) as? String
            if (marker == NotifyManager.OWNER_MARKER_VALUE) return true
            if (categoryIdentifier?.startsWith(NotifyManager.CATEGORY_ID_PREFIX) == true) return true
            return false
        }

        fun payloadFromUserInfo(info: Map<Any?, *>?): NotifyPayload? {
            if (info == null) return null
            if (!isNotifyOwned(info)) return null
            val id = info["id"] as? String ?: return null
            val title = info["title"] as? String ?: return null
            val body = info["body"] as? String ?: ""
            val data = mutableMapOf<String, String>()
            info.forEach { (k, v) ->
                val key = k as? String ?: return@forEach
                if (key.startsWith("data_") && v is String) {
                    data[key.removePrefix("data_")] = v
                }
            }
            return NotifyPayload(
                id = id,
                title = title,
                body = body,
                imageUrl = (info["imageUrl"] as? String)?.takeIf { it.isNotBlank() },
                deepLink = (info["deepLink"] as? String)?.takeIf { it.isNotBlank() },
                channelId = (info["channelId"] as? String)?.takeIf { it.isNotBlank() },
                actions = NotifyRemoteParser.parseActions(info["actions"] as? String),
                data = data,
                snoozeMinutes = (info["snoozeMinutes"] as? String)?.toIntOrNull(),
                groupKey = (info["groupKey"] as? String)?.takeIf { it.isNotBlank() },
                entityId = (info["entityId"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

class NotifyIosDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val content = didReceiveNotificationResponse.notification.request.content
        val info = content.userInfo
        val categoryId = content.categoryIdentifier

        if (!IosPlatformNotifier.isNotifyOwned(info, categoryId)) {
            forwardDidReceive(center, didReceiveNotificationResponse, withCompletionHandler)
            return
        }

        val payload = IosPlatformNotifier.payloadFromUserInfo(info)
        val mgr = NotifyManagerHolder.getOrCreate()
        payload?.let { mgr.seedCache(it) }

        val actionId = when (val aid = didReceiveNotificationResponse.actionIdentifier) {
            "com.apple.UNNotificationDefaultActionIdentifier" -> NotifyActionId.OPEN
            else -> NotifyActionId.fromWire(aid) ?: NotifyActionId.OPEN
        }
        val id = payload?.id
            ?: didReceiveNotificationResponse.notification.request.identifier.removePrefix("snooze_")
        mgr.dispatchAction(actionId, id)
        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        val content = willPresentNotification.request.content
        if (!IosPlatformNotifier.isNotifyOwned(content.userInfo, content.categoryIdentifier)) {
            forwardWillPresent(center, willPresentNotification, withCompletionHandler)
            return
        }
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or
                UNNotificationPresentationOptionList or
                UNNotificationPresentationOptionSound
        )
    }

    private fun forwardDidReceive(
        center: UNUserNotificationCenter,
        response: UNNotificationResponse,
        completion: () -> Unit,
    ) {
        val prev = NotifyIosDelegateInstaller.previous
        if (prev == null) {
            completion()
            return
        }
        val forwarded = runCatching {
            prev.userNotificationCenter(center, response, completion)
            true
        }.getOrDefault(false)
        if (!forwarded) completion()
    }

    private fun forwardWillPresent(
        center: UNUserNotificationCenter,
        notification: UNNotification,
        completion: (UNNotificationPresentationOptions) -> Unit,
    ) {
        val prev = NotifyIosDelegateInstaller.previous
        if (prev == null) {
            completion(UNNotificationPresentationOptionNone)
            return
        }
        val forwarded = runCatching {
            prev.userNotificationCenter(center, notification, completion)
            true
        }.getOrDefault(false)
        if (!forwarded) completion(UNNotificationPresentationOptionNone)
    }
}

private object NotifyIosDelegateInstaller {
    private var installed = false
    private val delegate = NotifyIosDelegate()

    /** Previous host/Firebase delegate; Notify forwards non-owned responses to it. */
    var previous: UNUserNotificationCenterDelegateProtocol? = null
        private set

    fun installIfNeeded() {
        if (installed) return
        installed = true
        val center = UNUserNotificationCenter.currentNotificationCenter()
        previous = center.delegate
        center.delegate = delegate
    }
}
