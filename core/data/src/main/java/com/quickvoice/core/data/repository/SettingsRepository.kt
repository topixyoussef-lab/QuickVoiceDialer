package com.quickvoice.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.QuickVoiceSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "quickvoice_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.dataStore

    private object Keys {
        val QUICK_VOICE_ENABLED = booleanPreferencesKey("quick_voice_enabled")
        val QUICK_VOICE_MAX_DURATION = longPreferencesKey("quick_voice_max_duration_ms")
        val QUICK_VOICE_AUTO_ACTIVATE = longPreferencesKey("quick_voice_auto_activate_ms")
        val QUICK_VOICE_AUTO_SPEAKER = booleanPreferencesKey("quick_voice_auto_speaker")
        val VOIP_USER_ID = stringPreferencesKey("voip_user_id")
        val VOIP_DISPLAY_NAME = stringPreferencesKey("voip_display_name")
        val VOIP_TURN_URL = stringPreferencesKey("voip_turn_url")
        val VOIP_TURN_USERNAME = stringPreferencesKey("voip_turn_username")
        val VOIP_TURN_PASSWORD = stringPreferencesKey("voip_turn_password")
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
        val DEFAULT_CALL_TYPE = stringPreferencesKey("default_call_type")
        val START_WITH_SPEAKER = booleanPreferencesKey("start_with_speaker")
        val RINGTONE_URI = stringPreferencesKey("ringtone_uri")
        val BACKGROUND_SERVICE_ENABLED = booleanPreferencesKey("background_service_enabled")
        val DEFAULT_DIALER_BANNER_DISMISSED = booleanPreferencesKey("default_dialer_banner_dismissed")
    }

    val quickVoiceSettings: Flow<QuickVoiceSettings> = dataStore.data.map { p ->
        QuickVoiceSettings(
            enabled = p[Keys.QUICK_VOICE_ENABLED] ?: false,
            maxMessageDurationMs = (p[Keys.QUICK_VOICE_MAX_DURATION] ?: 3_000L).coerceIn(2_000L, 5_000L),
            autoActivateAfterMs = (p[Keys.QUICK_VOICE_AUTO_ACTIVATE] ?: 15_000L).coerceAtLeast(5_000L),
            autoEnableSpeaker = p[Keys.QUICK_VOICE_AUTO_SPEAKER] ?: true,
        )
    }

    suspend fun setQuickVoiceSettings(settings: QuickVoiceSettings) {
        dataStore.edit { p ->
            p[Keys.QUICK_VOICE_ENABLED] = settings.enabled
            p[Keys.QUICK_VOICE_MAX_DURATION] = settings.maxMessageDurationMs.coerceIn(2_000L, 5_000L)
            p[Keys.QUICK_VOICE_AUTO_ACTIVATE] = settings.autoActivateAfterMs.coerceAtLeast(5_000L)
            p[Keys.QUICK_VOICE_AUTO_SPEAKER] = settings.autoEnableSpeaker
        }
    }

    suspend fun setQuickVoiceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.QUICK_VOICE_ENABLED] = enabled }
    }

    val voipUserId: Flow<String> = dataStore.data.map {
        val saved = it[Keys.VOIP_USER_ID].orEmpty()
        if (AUTO_ASSIGNED_HEX_ID.matches(saved)) "" else saved
    }

    suspend fun setVoipUserId(id: String) {
        dataStore.edit { it[Keys.VOIP_USER_ID] = id.trim() }
    }

    val voipDisplayName: Flow<String> = dataStore.data.map {
        it[Keys.VOIP_DISPLAY_NAME] ?: "QuickVoice-${android.os.Build.MODEL}"
    }

    suspend fun setVoipDisplayName(name: String) {
        dataStore.edit { it[Keys.VOIP_DISPLAY_NAME] = name.trim() }
    }

    val voipTurnUrl: Flow<String> = dataStore.data.map { it[Keys.VOIP_TURN_URL].orEmpty() }

    suspend fun setVoipTurnUrl(url: String) {
        dataStore.edit { it[Keys.VOIP_TURN_URL] = url.trim().removeSuffix("/") }
    }

    val voipTurnUsername: Flow<String> = dataStore.data.map { it[Keys.VOIP_TURN_USERNAME].orEmpty() }

    suspend fun setVoipTurnUsername(username: String) {
        dataStore.edit { it[Keys.VOIP_TURN_USERNAME] = username.trim() }
    }

    val voipTurnPassword: Flow<String> = dataStore.data.map { it[Keys.VOIP_TURN_PASSWORD].orEmpty() }

    suspend fun setVoipTurnPassword(password: String) {
        dataStore.edit { it[Keys.VOIP_TURN_PASSWORD] = password }
    }

    val lastUpdateCheckMs: Flow<Long> = dataStore.data.map { it[Keys.LAST_UPDATE_CHECK_MS] ?: 0L }

    suspend fun setLastUpdateCheckMs(ms: Long) {
        dataStore.edit { it[Keys.LAST_UPDATE_CHECK_MS] = ms }
    }

    val defaultCallType: Flow<CallType> = dataStore.data.map { p ->
        runCatching { CallType.valueOf(p[Keys.DEFAULT_CALL_TYPE] ?: "VOIP") }.getOrDefault(CallType.VOIP)
    }

    suspend fun setDefaultCallType(type: CallType) {
        dataStore.edit { it[Keys.DEFAULT_CALL_TYPE] = type.name }
    }

    val startWithSpeaker: Flow<Boolean> = dataStore.data.map { it[Keys.START_WITH_SPEAKER] ?: false }

    suspend fun setStartWithSpeaker(on: Boolean) {
        dataStore.edit { it[Keys.START_WITH_SPEAKER] = on }
    }

    /**
     * Incoming-call ringtone. An empty string means the bundled QuickVoice ringtone,
     * the literal value "silent" means no ringtone, and anything else is a content://
     * or file:// URI picked from the system ringtone picker.
     */
    val ringtoneUri: Flow<String> = dataStore.data.map { it[Keys.RINGTONE_URI].orEmpty() }

    suspend fun setRingtoneUri(uri: String) {
        dataStore.edit { it[Keys.RINGTONE_URI] = uri }
    }

    /** Whether the app should keep its signaling connection alive in the background. */
    val backgroundServiceEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.BACKGROUND_SERVICE_ENABLED] ?: true }

    suspend fun setBackgroundServiceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_SERVICE_ENABLED] = enabled }
    }

    val defaultDialerBannerDismissed: Flow<Boolean> = dataStore.data.map { it[Keys.DEFAULT_DIALER_BANNER_DISMISSED] ?: false }

    suspend fun setDefaultDialerBannerDismissed(dismissed: Boolean) {
        dataStore.edit { it[Keys.DEFAULT_DIALER_BANNER_DISMISSED] = dismissed }
    }

    companion object {
        /** Old server-assigned ids were 8 lowercase hex chars; treat them as unset so a friendly number is assigned. */
        private val AUTO_ASSIGNED_HEX_ID = Regex("^[0-9a-f]{8}$")
    }
}
