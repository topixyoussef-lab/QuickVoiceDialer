package com.quickvoice.core.voip

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Lightweight trampoline used as the full-screen intent of the incoming-call
 * notification. It brings the app's call screen to the foreground and finishes.
 */
class VoipIncomingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(
                Intent(ACTION_SHOW_CALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setPackage(packageName)
            )
        } catch (t: Throwable) {
            // Ignored: user will interact with the notification instead.
        }
        finish()
    }

    private companion object {
        const val ACTION_SHOW_CALL = "com.quickvoice.dialer.action.SHOW_CALL"
    }
}
