package org.transline.geoworker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class GeoNotificationHelper(private val context: Context) {

    private val channelId = "geo_worker_channel"

    companion object {
        /**
         * N1 geo success shade — OFF for product (roadmap).
         * Set true only in DEV harness if you need legacy shade spam.
         */
        @JvmField
        var enableGeoSuccessShade: Boolean = false
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Трекинг геолокации",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showSuccessNotification(lat: Double, lon: Double) {
        if (!enableGeoSuccessShade) return
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Транслайн Гео")
            .setContentText("Геокоординаты успешно отправлены ($lat, $lon)")
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }

    fun showOfflineNotification() {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Транслайн Гео")
            .setContentText("Нет сети. Данные сохранены локально и будут отправлены позже.")
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1002, notification)
    }
}
