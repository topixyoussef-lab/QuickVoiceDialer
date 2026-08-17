package com.quickvoice.core.voip.voip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts the background keep-alive service after a reboot.
 *
 * Android 12+ does not allow starting a remoteMessaging foreground service from
 * BOOT_COMPLETED, so on those versions the service simply resumes on the next app
 * launch (the service is otherwise kept alive by START_STICKY while the process lives).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        runCatching { BackgroundVoipService.start(context) }
    }
}
