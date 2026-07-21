package org.transline.geoworker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.transline.geoworker.tracker.TrackingListener

/**
 * Foreground Service для continuous GPS (аналог фонового LocationService на iOS).
 * Держит процесс живым и возобновляет трекинг через [GeoWorkerRuntime] (один controller).
 */
class LocationForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private var controller: org.transline.geoworker.tracker.LocationTrackerController? = null
    private var fgsListener: TrackingListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                bindControllerAndResume()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Не вызываем stopLocationService(): иначе сбросим tracking_active при kill процесса
        // и BootReceiver не сможет восстановить трекинг.
        detachFgsListener()
        controller = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("Трекинг геолокации активен")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun bindControllerAndResume() {
        val storage = GeoWorkerRuntime.storage(applicationContext)
        if (!storage.isTrackingActive()) {
            Log.d(TAG, "Tracking not active — stopping FGS")
            stopTrackingAndSelf()
            return
        }

        val ctrl = GeoWorkerRuntime.controller(applicationContext)
        if (controller !== ctrl) {
            detachFgsListener()
            val listener = object : TrackingListener {
                override fun onLocationSent(latitude: Double, longitude: Double, timestamp: Long) {
                    updateNotification("Координаты отправлены ($latitude, $longitude)")
                }

                override fun onLocationFailed(message: String) {
                    updateNotification("Офлайн: данные в очереди")
                }

                override fun onLocationServicesDisabled() {
                    updateNotification("Геолокация недоступна")
                }
            }
            ctrl.addListener(listener)
            fgsListener = listener
            controller = ctrl
        }

        val resumed = ctrl.resumeLocationServiceIfActive()
        Log.d(TAG, "resumeLocationServiceIfActive=$resumed")

        serviceScope.launch {
            try {
                ctrl.initializeAndSyncOnAppStart()
            } catch (e: Exception) {
                Log.e(TAG, "initializeAndSyncOnAppStart failed: ${e.message}")
            }
        }
    }

    private fun detachFgsListener() {
        val listener = fgsListener ?: return
        controller?.removeListener(listener)
        fgsListener = null
    }

    private fun stopTrackingAndSelf() {
        detachFgsListener()
        controller?.stopLocationService()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновый трекинг",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Транслайн Гео")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "LocationFGS"
        private const val CHANNEL_ID = "geo_worker_fgs"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "org.transline.geoworker.STOP_LOCATION_FGS"

        fun start(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, LocationForegroundService::class.java))
            }
        }
    }
}
