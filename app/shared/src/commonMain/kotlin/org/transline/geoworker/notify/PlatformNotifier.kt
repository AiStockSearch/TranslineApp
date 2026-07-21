package org.transline.geoworker.notify

/**
 * Platform display / schedule.
 * Android: call [NotifyAndroidContext.init] before [createPlatformNotifier].
 */
interface PlatformNotifier {
    fun show(payload: NotifyPayload): Boolean
    fun cancel(id: String)
    fun scheduleSnooze(payload: NotifyPayload, delayMinutes: Int)
}

expect fun createPlatformNotifier(): PlatformNotifier
