package com.quickvoice.feature.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickvoice.core.call.CallController
import com.quickvoice.core.data.repository.CallHistoryRepository
import com.quickvoice.core.data.repository.ContactsRepository
import com.quickvoice.core.data.repository.SavedNumberRepository
import com.quickvoice.core.data.repository.SettingsRepository
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.Contact
import com.quickvoice.core.model.RecentCall
import com.quickvoice.core.quickvoice.QuickVoiceController
import com.quickvoice.core.telecom.defaultdialer.DefaultDialerManager
import com.quickvoice.core.telecom.sim.SimCallManager
import com.quickvoice.core.update.UpdateManager
import com.quickvoice.core.update.UpdateState
import com.quickvoice.core.voip.model.SignalingState
import com.quickvoice.core.voip.voip.VoipCallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val dialNumber: String = "",
    val searchQuery: String = "",
    val contacts: List<Contact> = emptyList(),
    val savedContacts: List<Contact> = emptyList(),
    val recents: List<RecentCall> = emptyList(),
    val quickVoiceEnabled: Boolean = false,
    val defaultCallType: CallType = CallType.SIM,
    val voipUserId: String = "",
    val voipDisplayName: String = "",
    val isDefaultDialer: Boolean = false,
    val dialerBannerDismissed: Boolean = false,
    val signalingState: SignalingState = SignalingState.DISCONNECTED,
    val updateReady: Boolean = false,
    val updateVersionName: String = "",
    val crashReport: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactsRepository: ContactsRepository,
    private val savedNumberRepository: SavedNumberRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val simCallManager: SimCallManager,
    private val defaultDialerManager: DefaultDialerManager,
    private val voipCallManager: VoipCallManager,
    val quickVoiceController: QuickVoiceController,
    private val callController: CallController,
    private val updateManager: UpdateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var startWithSpeaker = false

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 15L * 60L * 1000L
    }

    init {
        viewModelScope.launch {
            settingsRepository.quickVoiceSettings.collect { s ->
                _uiState.update { it.copy(quickVoiceEnabled = s.enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultCallType.collect { t ->
                _uiState.update { it.copy(defaultCallType = t) }
            }
        }
        viewModelScope.launch {
            settingsRepository.startWithSpeaker.collect { startWithSpeaker = it }
        }
        viewModelScope.launch {
            voipCallManager.signalingState.collect { s ->
                _uiState.update { it.copy(signalingState = s) }
                if (s == SignalingState.REGISTERED) {
                    // Freshly connected to the server: check for updates right away.
                    checkForUpdate()
                }
            }
        }
        viewModelScope.launch {
            voipCallManager.userId.collect { id ->
                _uiState.update { it.copy(voipUserId = id) }
            }
        }
        viewModelScope.launch {
            settingsRepository.voipDisplayName.collect { name ->
                _uiState.update { it.copy(voipDisplayName = name) }
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultDialerBannerDismissed.collect { dismissed ->
                _uiState.update { it.copy(dialerBannerDismissed = dismissed) }
            }
        }
        viewModelScope.launch {
            voipCallManager.lastError.collect { e ->
                if (e != null) _uiState.update { it.copy(notice = e) }
            }
        }
        viewModelScope.launch {
            callHistoryRepository.recents.collect { recents ->
                _uiState.update { it.copy(recents = recents.take(100)) }
            }
        }
        viewModelScope.launch {
            savedNumberRepository.savedContacts.collect { saved ->
                _uiState.update { it.copy(savedContacts = saved) }
            }
        }
        viewModelScope.launch {
            updateManager.state.collect { s ->
                _uiState.update {
                    it.copy(
                        updateReady = s is UpdateState.Available && s.downloaded,
                        updateVersionName = (s as? UpdateState.Available)?.info?.versionName.orEmpty(),
                    )
                }
            }
        }
        startUpdateLoop()
        refreshDefaultDialer()
        searchContacts("")
        _uiState.update { it.copy(crashReport = readCrashReport()) }
    }

    /** Exception line of the last captured crash (skips the timestamp line), if any. */
    private fun readCrashReport(): String? {
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return null
        val file = File(dir, "last_crash.txt")
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val exception = lines.drop(1).firstOrNull { it.isNotBlank() } ?: lines.firstOrNull()
        if (exception.isNullOrBlank()) {
            file.delete()
            return null
        }
        return exception.take(300)
    }

    fun dismissCrashReport() {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, "last_crash.txt").delete()
        }
        _uiState.update { it.copy(crashReport = null) }
    }

    // ----------------------------------------------------------------- updates

    /**
     * Re-checks for updates immediately and then every 15 minutes while the
     * screen is open, so a freshly published version reaches the phone without
     * requiring a restart. An extra check also fires on [SignalingState.REGISTERED].
     */
    private fun startUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                checkForUpdate()
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkForUpdate() {
        settingsRepository.setLastUpdateCheckMs(System.currentTimeMillis())
        updateManager.checkNow()
        val info = (updateManager.state.value as? UpdateState.Available)?.info ?: return
        updateManager.download(info)
    }

    fun installPendingUpdate() {
        updateManager.installDownloaded()
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(updateReady = false, updateVersionName = "") }
    }

    // ------------------------------------------------------------------ state

    fun refreshDefaultDialer() {
        _uiState.update { it.copy(isDefaultDialer = defaultDialerManager.isDefaultDialer()) }
    }

    fun requestDialerRoleIntent(): Intent? = defaultDialerManager.requestDialerRoleIntent()

    fun dismissDialerBanner() {
        viewModelScope.launch {
            settingsRepository.setDefaultDialerBannerDismissed(true)
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    // ---------------------------------------------------------------- contacts

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchContacts(query)
    }

    fun searchContacts(query: String) {
        viewModelScope.launch {
            val voipLinks = contactsRepository.voipLinks.first().toMap()
            val contacts = contactsRepository.searchContacts(query.trim(), voipLinks)
            _uiState.update { it.copy(contacts = contacts) }
        }
    }

    // ------------------------------------------------------------ saved numbers

    fun saveNumber(number: String, name: String) {
        if (number.isBlank()) {
            _uiState.update { it.copy(notice = "Enter a number to save first") }
            return
        }
        viewModelScope.launch {
            savedNumberRepository.save(number, name)
            _uiState.update { it.copy(notice = "\"${name.trim().ifEmpty { number }}\" saved") }
        }
    }

    fun deleteSavedNumber(number: String) {
        viewModelScope.launch {
            savedNumberRepository.delete(number)
        }
    }

    fun isSavedNumber(number: String): Boolean =
        _uiState.value.savedContacts.any { it.phoneNumber == number }

    // ---------------------------------------------------------- leave a message

    fun startMessageTo(peerId: String, displayName: String) {
        quickVoiceController.startMessageTo(peerId, displayName)
    }

    /** Leaves a voice message for a phone number, routing via a linked VoIP ID when one is known. */
    fun sendVoiceMessageTo(number: String, displayName: String) {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val voipId = contactsRepository.voipIdForNumber(trimmed) ?: trimmed
            quickVoiceController.startMessageTo(voipId, displayName)
        }
    }

    fun stopMessageTo() {
        quickVoiceController.stopMessageTo()
    }

    fun cancelMessageMode() {
        quickVoiceController.cancelMessageMode()
    }

    // ----------------------------------------------------------------- dial pad

    fun onDialDigit(digit: String) {
        val max = 24
        _uiState.update { it.copy(dialNumber = (it.dialNumber + digit).take(max)) }
    }

    fun onBackspace() {
        _uiState.update { it.copy(dialNumber = it.dialNumber.dropLast(1)) }
    }

    fun onClearDial() {
        _uiState.update { it.copy(dialNumber = "") }
    }

    fun onPasteNumber(number: String) {
        _uiState.update { it.copy(dialNumber = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }.take(24)) }
    }

    fun setDefaultCallType(type: CallType) {
        viewModelScope.launch { settingsRepository.setDefaultCallType(type) }
    }

    // ------------------------------------------------------------------ calls

    fun setQuickVoiceEnabled(enabled: Boolean) {
        quickVoiceController.setEnabled(enabled)
    }

    fun placeCall(number: String, type: CallType = _uiState.value.defaultCallType) {
        if (number.isBlank()) {
            _uiState.update { it.copy(notice = "Enter a number or pick a contact first") }
            return
        }
        when (type) {
            CallType.SIM -> {
                val ok = simCallManager.placeCall(number, startWithSpeaker)
                if (ok) {
                    if (!_uiState.value.isDefaultDialer) {
                        _uiState.update {
                            it.copy(
                                notice = "Call placed via SIM. Make QuickVoice the default phone app " +
                                    "to keep the in-app call screen and Quick Voice controls."
                            )
                        }
                    }
                } else {
                    _uiState.update { it.copy(notice = "Could not place the SIM call. Check CALL_PHONE permission.") }
                }
            }

            CallType.VOIP -> {
                if (_uiState.value.signalingState != SignalingState.REGISTERED) {
                    _uiState.update { it.copy(notice = "Wi-Fi calling needs the VoIP server. Connect it in Settings.") }
                    return
                }
                voipCallManager.placeCall(number, number)
            }
        }
    }

    fun placeContact(contact: Contact) {
        val type = if (contact.voipId != null) CallType.VOIP else CallType.SIM
        placeCall(contact.voipId ?: contact.phoneNumber, type)
    }

    fun callContactViaSim(contact: Contact) = placeCall(contact.phoneNumber, CallType.SIM)

    fun callContactViaVoip(contact: Contact) {
        val voipId = contact.voipId ?: return
        placeCall(voipId, CallType.VOIP)
    }

    /** One-way intercom to a VoIP contact: their phone auto-answers so we can talk. */
    fun intercomContact(contact: Contact) {
        val voipId = contact.voipId ?: return
        if (_uiState.value.signalingState != SignalingState.REGISTERED) {
            _uiState.update { it.copy(notice = "Intercom needs the VoIP server. Connect it in Settings.") }
            return
        }
        voipCallManager.placeIntercom(voipId, contact.name)
    }

    /** One-way intercom dialed from the dial pad (the number is the peer VoIP ID). */
    fun intercomNumber(number: String) {
        if (number.isBlank()) {
            _uiState.update { it.copy(notice = "Enter a number or pick a contact first") }
            return
        }
        if (_uiState.value.signalingState != SignalingState.REGISTERED) {
            _uiState.update { it.copy(notice = "Intercom needs the VoIP server. Connect it in Settings.") }
            return
        }
        voipCallManager.placeIntercom(number, number)
    }

    fun placeRecent(call: RecentCall) = placeCall(call.number, call.type)

    // ------------------------------------------------------------- has session

    val hasActiveCall: Boolean
        get() = callController.activeSession.value != null
}
