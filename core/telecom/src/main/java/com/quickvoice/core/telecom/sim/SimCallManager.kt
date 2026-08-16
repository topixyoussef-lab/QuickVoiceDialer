package com.quickvoice.core.telecom.sim

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places regular SIM calls through the official Telecom framework.
 *
 * Requirements (Android 10+): the caller must hold CALL_PHONE (requested at runtime)
 * and/or be the default dialer. When the app is the default dialer our
 * [CallInCallService] receives the resulting call and publishes it to the app.
 */
@Singleton
class SimCallManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val telecomManager = appContext.getSystemService(TelecomManager::class.java)

    val isDefaultDialer: Boolean
        get() = telecomManager.defaultDialerPackage == appContext.packageName

    /**
     * Places a SIM call. Returns false on failure (e.g. missing permission).
     */
    fun placeCall(number: String, startWithSpeaker: Boolean): Boolean {
        if (number.isBlank()) return false
        return try {
            val uri = Uri.fromParts("tel", number, null)
            val extras = Bundle().apply {
                if (startWithSpeaker) {
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                }
            }
            telecomManager.placeCall(uri, extras)
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "placeCall requires CALL_PHONE permission and/or default dialer", se)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "placeCall failed", t)
            false
        }
    }

    /** SIM accounts the user enabled for placing calls. */
    fun getCallCapablePhoneAccounts(): List<PhoneAccountHandle> = try {
        telecomManager.callCapablePhoneAccounts
    } catch (t: Throwable) {
        Log.w(TAG, "getCallCapablePhoneAccounts failed", t)
        emptyList()
    }

    private companion object {
        const val TAG = "SimCallManager"
    }
}
