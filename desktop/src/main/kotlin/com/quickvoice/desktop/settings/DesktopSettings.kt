package com.quickvoice.desktop.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * Desktop replacement for the Android SettingsRepository. Persists the same keys
 * to a simple properties file in the user's home directory so the desktop app can
 * register to the same signaling server as the phone app.
 */
class DesktopSettings(private val file: File) {

    private val props = Properties()

    init {
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "QuickVoice Desktop settings") }
        }
        refresh()
    }

    private fun refresh() {
        _serverUrl.value = get(KEY_SERVER_URL, DEFAULT_SERVER_URL)
        _userId.value = get(KEY_USER_ID, "")
        _displayName.value = get(KEY_DISPLAY_NAME, "QuickVoice-PC")
        _turnUrl.value = get(KEY_TURN_URL, "")
        _turnUsername.value = get(KEY_TURN_USERNAME, "")
        _turnPassword.value = get(KEY_TURN_PASSWORD, "")
    }

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: Flow<String> = _serverUrl.asStateFlow()

    private val _userId = MutableStateFlow("")
    val userId: Flow<String> = _userId.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: Flow<String> = _displayName.asStateFlow()

    private val _turnUrl = MutableStateFlow("")
    val turnUrl: Flow<String> = _turnUrl.asStateFlow()

    private val _turnUsername = MutableStateFlow("")
    val turnUsername: Flow<String> = _turnUsername.asStateFlow()

    private val _turnPassword = MutableStateFlow("")
    val turnPassword: Flow<String> = _turnPassword.asStateFlow()

    init {
        refresh()
    }

    fun setServerUrl(url: String) { props.setProperty(KEY_SERVER_URL, url.trim().removeSuffix("/")); save() }
    fun setUserId(id: String) { props.setProperty(KEY_USER_ID, id.trim()); save() }
    fun setDisplayName(name: String) { props.setProperty(KEY_DISPLAY_NAME, name.trim()); save() }
    fun setTurnUrl(url: String) { props.setProperty(KEY_TURN_URL, url.trim().removeSuffix("/")); save() }
    fun setTurnUsername(username: String) { props.setProperty(KEY_TURN_USERNAME, username.trim()); save() }
    fun setTurnPassword(password: String) { props.setProperty(KEY_TURN_PASSWORD, password); save() }

    fun currentServerUrl(): String = _serverUrl.value
    fun currentUserId(): String = _userId.value
    fun currentDisplayName(): String = _displayName.value

    private fun get(key: String, default: String): String =
        props.getProperty(key) ?: default

    companion object {
        private const val KEY_SERVER_URL = "voip_server_url"
        private const val KEY_USER_ID = "voip_user_id"
        private const val KEY_DISPLAY_NAME = "voip_display_name"
        private const val KEY_TURN_URL = "voip_turn_url"
        private const val KEY_TURN_USERNAME = "voip_turn_username"
        private const val KEY_TURN_PASSWORD = "voip_turn_password"
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.6:8080/signaling"

        fun defaultFile(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(File(home, ".quickvoice"), "desktop.properties")
        }
    }
}
