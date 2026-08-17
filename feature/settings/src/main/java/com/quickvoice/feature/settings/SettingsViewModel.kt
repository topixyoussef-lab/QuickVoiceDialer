package com.quickvoice.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickvoice.core.audio.CallRecordingPlayer
import com.quickvoice.core.audio.CallRingTone
import com.quickvoice.core.data.repository.CallRecording
import com.quickvoice.core.data.repository.CallRecordingRepository
import com.quickvoice.core.data.repository.SettingsRepository
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.QuickVoiceSettings
import com.quickvoice.core.telecom.defaultdialer.DefaultDialerManager
import com.quickvoice.core.update.UpdateManager
import com.quickvoice.core.update.UpdateState
import com.quickvoice.core.voip.model.SignalingState
import com.quickvoice.core.voip.voip.BackgroundVoipService
import com.quickvoice.core.voip.voip.VoipCallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A single runtime permission with the rationale we show to the user. */
data class PermissionRow(
    val permission: String,
    val label: String,
    val explanation: String,
    val granted: Boolean,
)

data class SettingsUiState(
    val quickVoice: QuickVoiceSettings = QuickVoiceSettings(),
    val startWithSpeaker: Boolean = false,
    val defaultCallType: CallType = CallType.SIM,
    val ringtoneUri: String = "",
    val ringtoneName: String = "QuickVoice ringtone",
    val playingRingtone: Boolean = false,
    val backgroundServiceEnabled: Boolean = true,
    val backgroundServiceRunning: Boolean = false,
    val voipUserId: String = "",
    val voipDisplayName: String = "",
    val voipTurnUrl: String = "",
    val voipTurnUsername: String = "",
    val voipTurnPassword: String = "",
    val signalingState: SignalingState = SignalingState.DISCONNECTED,
    val isDefaultDialer: Boolean = false,
    val permissions: List<PermissionRow> = emptyList(),
    val installedVersion: String = "",
    val updateState: UpdateState = UpdateState.Idle,
    val canInstallPackages: Boolean = true,
    val recordings: List<CallRecording> = emptyList(),
    val playingRecordingPath: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val voipCallManager: VoipCallManager,
    private val callRingTone: CallRingTone,
    private val defaultDialerManager: DefaultDialerManager,
    private val updateManager: UpdateManager,
    private val recordingRepository: CallRecordingRepository,
    private val recordingPlayer: CallRecordingPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.quickVoiceSettings.collect { s ->
                _uiState.update { it.copy(quickVoice = s) }
            }
        }
        viewModelScope.launch {
            settingsRepository.startWithSpeaker.collect { on ->
                _uiState.update { it.copy(startWithSpeaker = on) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultCallType.collect { t ->
                _uiState.update { it.copy(defaultCallType = t) }
            }
        }
        viewModelScope.launch {
            settingsRepository.ringtoneUri.collect { uri ->
                _uiState.update {
                    it.copy(ringtoneUri = uri, ringtoneName = resolveRingtoneName(uri))
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.backgroundServiceEnabled.collect { on ->
                _uiState.update { it.copy(backgroundServiceEnabled = on) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipUserId.collect { id ->
                _uiState.update { it.copy(voipUserId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipDisplayName.collect { name ->
                _uiState.update { it.copy(voipDisplayName = name) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipTurnUrl.collect { url ->
                _uiState.update { it.copy(voipTurnUrl = url) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipTurnUsername.collect { username ->
                _uiState.update { it.copy(voipTurnUsername = username) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipTurnPassword.collect { password ->
                _uiState.update { it.copy(voipTurnPassword = password) }
            }
        }
        viewModelScope.launch {
            voipCallManager.signalingState.collect { s ->
                _uiState.update { it.copy(signalingState = s) }
            }
        }
        viewModelScope.launch {
            updateManager.state.collect { s ->
                _uiState.update {
                    it.copy(
                        updateState = s,
                        canInstallPackages = updateManager.canRequestPackageInstalls(),
                    )
                }
            }
        }
        refreshDialerStatus()
        refreshPermissions()
        refreshRecordings()
        refreshBackgroundStatus()
        _uiState.update { it.copy(installedVersion = updateManager.installedVersionName) }
    }

    // -------------------------------------------------------------- quick voice

    fun setQuickVoiceEnabled(enabled: Boolean) {
        updateQuickVoice { it.copy(enabled = enabled) }
    }

    fun setMaxDurationMs(ms: Long) {
        updateQuickVoice { it.copy(maxMessageDurationMs = ms.coerceIn(2_000L, 5_000L)) }
    }

    fun setAutoActivateMs(ms: Long) {
        updateQuickVoice { it.copy(autoActivateAfterMs = ms.coerceAtLeast(5_000L)) }
    }

    fun setAutoSpeaker(on: Boolean) {
        updateQuickVoice { it.copy(autoEnableSpeaker = on) }
    }

    private fun updateQuickVoice(transform: (QuickVoiceSettings) -> QuickVoiceSettings) {
        val current = _uiState.value.quickVoice
        viewModelScope.launch {
            settingsRepository.setQuickVoiceSettings(transform(current))
        }
    }

    // ------------------------------------------------------------------ audio

    fun setStartWithSpeaker(on: Boolean) {
        viewModelScope.launch { settingsRepository.setStartWithSpeaker(on) }
    }

    fun setDefaultCallType(type: CallType) {
        viewModelScope.launch { settingsRepository.setDefaultCallType(type) }
    }

    // ----------------------------------------------------------------- ringtone

    fun setRingtoneUri(uri: String) {
        viewModelScope.launch { settingsRepository.setRingtoneUri(uri) }
    }

    /** Called with the URI the system ringtone picker returned (null = cancelled). */
    fun onRingtonePicked(uri: Uri?) {
        if (uri == null) return
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        // The picker's "default" option maps to the bundled QuickVoice ringtone.
        setRingtoneUri(if (uri == defaultUri) "" else uri.toString())
    }

    fun ringtonePickerIntent(): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Incoming call ringtone")
            val current = _uiState.value.ringtoneUri
            if (current.isNotBlank() && current != SILENT_RINGTONE) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
            }
        }

    fun previewRingtone() {
        callRingTone.onPreviewCompletion = {
            _uiState.update { it.copy(playingRingtone = false) }
        }
        _uiState.update { it.copy(playingRingtone = true) }
        callRingTone.previewRingtone(_uiState.value.ringtoneUri)
    }

    fun stopRingtonePreview() {
        callRingTone.stop()
        _uiState.update { it.copy(playingRingtone = false) }
    }

    private fun resolveRingtoneName(uriString: String): String = when {
        uriString == SILENT_RINGTONE -> "Silent"
        uriString.isBlank() -> "QuickVoice ringtone"
        else -> runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(uriString))?.getTitle(context)
        }.getOrNull() ?: "Custom ringtone"
    }

    // --------------------------------------------------------------- background

    fun setBackgroundServiceEnabled(on: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBackgroundServiceEnabled(on)
            if (on) {
                runCatching { BackgroundVoipService.start(context) }
            } else {
                BackgroundVoipService.stop(context)
            }
            refreshBackgroundStatus()
        }
    }

    fun refreshBackgroundStatus() {
        _uiState.update { it.copy(backgroundServiceRunning = BackgroundVoipService.isRunning()) }
    }

    // ------------------------------------------------------------------- voip

    fun saveVoipSettings(
        userId: String,
        displayName: String,
        turnUrl: String = _uiState.value.voipTurnUrl,
        turnUsername: String = _uiState.value.voipTurnUsername,
        turnPassword: String = _uiState.value.voipTurnPassword,
    ) {
        viewModelScope.launch {
            settingsRepository.setVoipUserId(userId)
            settingsRepository.setVoipDisplayName(displayName)
            settingsRepository.setVoipTurnUrl(turnUrl)
            settingsRepository.setVoipTurnUsername(turnUsername)
            settingsRepository.setVoipTurnPassword(turnPassword)
            _uiState.update {
                it.copy(
                    voipUserId = userId,
                    voipDisplayName = displayName,
                    voipTurnUrl = turnUrl,
                    voipTurnUsername = turnUsername,
                    voipTurnPassword = turnPassword,
                )
            }
        }
    }

    fun connectVoip() {
        viewModelScope.launch {
            voipCallManager.startServerConnection()
            _uiState.update { it.copy(notice = "Connecting to the VoIP server…") }
        }
    }

    fun disconnectVoip() {
        voipCallManager.stopServerConnection()
        _uiState.update { it.copy(notice = "VoIP server disconnected") }
    }

    // ----------------------------------------------------------------- updates

    fun checkForUpdates() {
        viewModelScope.launch { updateManager.checkNow() }
    }

    fun downloadUpdate() {
        val info = (_uiState.value.updateState as? UpdateState.Available)?.info ?: return
        viewModelScope.launch { updateManager.download(info) }
    }

    fun installUpdate() {
        updateManager.installDownloaded()
    }

    /** Opens the system "install unknown apps" screen for this app. */
    fun openInstallUnknownAppsSettings(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        }
        return null
    }

    fun updateCanInstallPackages() {
        _uiState.update { it.copy(canInstallPackages = updateManager.canRequestPackageInstalls()) }
    }

    // ----------------------------------------------------------- phone app role

    fun refreshDialerStatus() {
        _uiState.update { it.copy(isDefaultDialer = defaultDialerManager.isDefaultDialer()) }
    }

    fun requestDialerRoleIntent(): Intent? = defaultDialerManager.requestDialerRoleIntent()

    // -------------------------------------------------------------- recordings

    fun refreshRecordings() {
        _uiState.update { it.copy(recordings = recordingRepository.list()) }
    }

    fun playRecording(recording: CallRecording) {
        if (_uiState.value.playingRecordingPath == recording.path) {
            recordingPlayer.stop()
            _uiState.update { it.copy(playingRecordingPath = null) }
            return
        }
        recordingPlayer.onCompletion = {
            _uiState.update { it.copy(playingRecordingPath = null) }
        }
        recordingPlayer.play(recording.path)
        _uiState.update { it.copy(playingRecordingPath = recording.path) }
    }

    fun deleteRecording(recording: CallRecording) {
        recordingPlayer.stop()
        if (recordingRepository.delete(recording)) {
            _uiState.update {
                it.copy(
                    recordings = it.recordings.filterNot { r -> r.path == recording.path },
                    playingRecordingPath = null,
                )
            }
        }
    }


    // -------------------------------------------------------------- permissions

    fun refreshPermissions() {
        val rows = listOf(
            PermissionRow(
                permission = Manifest.permission.RECORD_AUDIO,
                label = "Microphone",
                explanation = "Required to record Quick Voice messages and to make Wi-Fi calls.",
                granted = granted(Manifest.permission.RECORD_AUDIO),
            ),
            PermissionRow(
                permission = Manifest.permission.READ_CONTACTS,
                label = "Contacts",
                explanation = "Lets you dial straight from your address book.",
                granted = granted(Manifest.permission.READ_CONTACTS),
            ),
            PermissionRow(
                permission = Manifest.permission.CALL_PHONE,
                label = "Phone",
                explanation = "Required by Android to place regular SIM calls.",
                granted = granted(Manifest.permission.CALL_PHONE),
            ),
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                PermissionRow(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    label = "Notifications",
                    explanation = "Shows incoming Wi-Fi call notifications.",
                    granted = granted(Manifest.permission.POST_NOTIFICATIONS),
                )
            )
        } else {
            emptyList()
        }
        _uiState.update { it.copy(permissions = rows) }
    }

    fun missingPermissions(): Array<String> =
        _uiState.value.permissions.filterNot { it.granted }.map { it.permission }.toTypedArray()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private companion object {
        const val SILENT_RINGTONE = "silent"
    }

    /** Refresh helper used after returning from the permission / role dialogs. */
    suspend fun refreshFromDisk() {
        settingsRepository.quickVoiceSettings.first()
        settingsRepository.startWithSpeaker.first()
        settingsRepository.defaultCallType.first()
        settingsRepository.voipUserId.first()
        settingsRepository.voipDisplayName.first()
        refreshDialerStatus()
        refreshPermissions()
    }
}
