package com.quickvoice.core.voip.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps the VoIP call (and the process) alive while a call is
 * active or incoming. It is started from the foreground when the user places or answers
 * a VoIP call, which is permitted by the Android background-start restrictions. The
 * actual logic lives in [VoipCallManager]; this service owns the notification + actions.
 */
@AndroidEntryPoint
class VoipCallService : Service() {

    @Inject
    lateinit var voipCallManager: VoipCallManager

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "VoIP calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                startForeground(NOTIFICATION_ID, ongoingCallNotification(name.ifBlank { number.ifBlank { "VoIP call" } }, number))
            }

            ACTION_SHOW_INCOMING -> {
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                startForeground(NOTIFICATION_ID, incomingCallNotification(name.ifBlank { number }))
            }

            ACTION_ANSWER -> voipCallManager.acceptIncomingCall()
            ACTION_DECLINE -> voipCallManager.declineIncomingCall()
            ACTION_HANGUP -> voipCallManager.hangup()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun serviceIntent(action: String, extraName: String = "", extraValue: String = ""): Intent =
        Intent(this, VoipCallService::class.java).setAction(action)
            .putExtra(extraName, extraValue)

    private fun ongoingCallNotification(title: String, subtitle: String): Notification {
        val hangup = PendingIntent.getService(
            this, 1, serviceIntent(ACTION_HANGUP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "VoIP call in progress" })
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColor(Color.rgb(0x00, 0x69, 0x6E))
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End call",
                hangup,
            ).build())
            .build()
    }

    private fun incomingCallNotification(title: String): Notification {
        val answer = PendingIntent.getService(
            this, 2, serviceIntent(ACTION_ANSWER), PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getService(
            this, 3, serviceIntent(ACTION_DECLINE), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Incoming VoIP call")
            .setContentText("$title is calling you")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(PendingIntent.getActivity(
                this,
                4,
                Intent(this, com.quickvoice.core.voip.VoipIncomingActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ), true)
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_call,
                "Answer",
                answer,
            ).build())
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                decline,
            ).build())
            .build()
    }

    companion object {
        const val CHANNEL_ID = "voip_calls"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.quickvoice.action.START_CALL"
        const val ACTION_SHOW_INCOMING = "com.quickvoice.action.SHOW_INCOMING"
        const val ACTION_ANSWER = "com.quickvoice.action.ANSWER"
        const val ACTION_DECLINE = "com.quickvoice.action.DECLINE"
        const val ACTION_HANGUP = "com.quickvoice.action.HANGUP"
        const val ACTION_STOP = "com.quickvoice.action.STOP"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_NUMBER = "extra_number"

        fun startCall(context: Context, name: String = "", number: String = "") {
            val intent = Intent(context, VoipCallService::class.java)
                .setAction(ACTION_START_CALL)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_NUMBER, number)
            context.startForegroundService(intent)
        }

        fun showIncoming(context: Context, name: String, number: String) {
            val intent = Intent(context, VoipCallService::class.java)
                .setAction(ACTION_SHOW_INCOMING)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_NUMBER, number)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoipCallService::class.java))
        }
    }
}
