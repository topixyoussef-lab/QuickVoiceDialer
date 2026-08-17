package com.quickvoice.core.telecom.defaultdialer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the role of being the system's default dialer / phone app.
 *
 * This role is required for the most interesting SIM-call features:
 *  - receiving [android.telecom.InCallService] callbacks (call state, audio route, mute);
 *  - controlling the speaker route through [android.telecom.Call.setAudioRoute];
 *  - auto-hanging-up an unanswered call.
 *
 * A normal app cannot do these on Android 10+ without this role, because
 * [android.media.AudioManager.setSpeakerphoneOn] was restricted and the only
 * supported audio routing API is the Telecom one (default dialer only).
 */
@Singleton
class DefaultDialerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val telecomManager = context.getSystemService(TelecomManager::class.java)

    fun isDefaultDialer(): Boolean =
        telecomManager.defaultDialerPackage == context.packageName

    /**
     * Returns the system role-request intent the user must confirm, or null if the
     * app already holds the role. Handle it with an ActivityResultLauncher and call
     * [isDefaultDialer] again in the callback.
     */
    fun requestDialerRoleIntent(): Intent? {
        if (isDefaultDialer()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }
}
