package com.quickvoice.core.voip.voip

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quickvoice.core.voip.model.SignalingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.app.NotificationChannel

/**
 * Foreground service that keeps the signaling connection alive so the user keeps
 * receiving Wi-Fi calls and voice messages while the app is not on screen.
 *
 * The app starts it from the foreground on launch, posts a low-priority persistent
 * notification and keeps [VoipCallManager]'s Firebase signaling connected. If Android kills the
 * process it is restarted via START_STICKY; [BootReceiver] restarts it after a reboot
 * (on API 12+ where that type of foreground service is allowed from boot).
 */
@AndroidEntryPoint
class BackgroundVoipService : Service() {

    @Inject lateinit var voipCallManager: VoipCallManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "QuickVoice background", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            started = true
            val notification = notification("QuickVoice", "Preparing…")
            // remoteMessaging is only a valid foreground service type from Android 14
            // (API 34); on older versions the type is taken from the manifest.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        // Reconnect Firebase signaling if it dropped while we were away.
        if (voipCallManager.signalingState.value == SignalingState.DISCONNECTED) {
            scope.launch {
                runCatching { voipCallManager.startServerConnection() }
            }
        }

        // Keep the notification text in sync with the connection state.
        scope.launch {
            voipCallManager.signalingState
                .combine(voipCallManager.userId) { state, id -> state to id }
                .collect { (state, id) ->
                    val (title, text) = when (state) {
                        SignalingState.CONNECTED ->
                            if (id.isNotBlank()) "Wi-Fi calls ready" to "Receiving calls as $id"
                            else "Wi-Fi calls ready" to "Connected"
                        else -> "QuickVoice" to "Reconnecting…"
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(title, text))
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(Color.rgb(0x00, 0x69, 0x6E))
            .setContentIntent(PendingIntent.getActivity(
                this,
                1,
                contextIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()

    private fun contextIntent(): Intent =
        Intent().apply {
            setPackage(packageName)
            setClassName(packageName, "com.quickvoice.app.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    companion object {
        const val CHANNEL_ID = "quickvoice_background"
        const val NOTIFICATION_ID = 2001

        @Volatile
        private var started = false

        fun isRunning() = started

        /** Starts the keep-alive service. Call from the foreground (app launch). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundVoipService::class.java))
        }

        /** Stops the keep-alive service; the signaling connection is left as-is. */
        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundVoipService::class.java))
        }
    }
}
