package org.transline.geoworker.notify

/** JVM / desktop stub — no system notifications. */
actual fun createPlatformNotifier(): PlatformNotifier = object : PlatformNotifier {
    override fun show(payload: NotifyPayload): Boolean = false
    override fun cancel(id: String) = Unit
    override fun scheduleSnooze(payload: NotifyPayload, delayMinutes: Int) = Unit
}
