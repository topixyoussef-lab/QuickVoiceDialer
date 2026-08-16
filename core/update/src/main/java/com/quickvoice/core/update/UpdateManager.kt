package com.quickvoice.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.quickvoice.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A published release fetched from the update endpoint. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
)

/** The current status of the self-update mechanism. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val info: UpdateInfo, val downloaded: Boolean = false) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update over the same host the app already talks to for signaling.
 *
 * The server exposes  GET /api/version  -> { versionName, versionCode, apkUrl }
 * and serves the APK under  /apk/<file>. The host is derived from the configured
 * signaling URL (ws:// -> http://, wss:// -> https://).
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Installed version of the app (from PackageManager, not BuildConfig). */
    val installedVersionName: String by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: ""
        }.getOrDefault("")
    }

    val installedVersionCode: Int by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    /** Does the OS allow this app to install other packages? */
    fun canRequestPackageInstalls(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private suspend fun baseHttpUrl(): String? {
        val wsUrl = settings.voipServerUrl.first().trim()
        if (wsUrl.isBlank()) return null
        val scheme = when {
            wsUrl.startsWith("wss://") -> "https"
            wsUrl.startsWith("ws://") -> "http"
            else -> return null
        }
        // The signaling URL carries a path (e.g. /signaling). Keep only the
        // scheme + host[:port] so the update endpoints (/api/version, /apk/...)
        // are hit on the server root.
        val rest = wsUrl.substringAfter("://")
        val hostPort = rest.substringBefore('/').trim()
        if (hostPort.isBlank()) return null
        return "$scheme://$hostPort"
    }

    /** Queries the server and compares with the installed version. Throws on connection/protocol errors. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val base = baseHttpUrl() ?: return@withContext null
        val request = Request.Builder().url("$base/api/version").build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server responded HTTP ${resp.code}")
            resp.body?.string()
        } ?: throw IOException("Empty response from server")
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("Invalid response from server")
        }
        val info = UpdateInfo(
            versionName = json.optString("versionName"),
            versionCode = json.optInt("versionCode", 0),
            apkUrl = json.optString("apkUrl", "/apk/QuickVoiceDialer.apk"),
        )
        if (info.versionCode > installedVersionCode) info else null
    }

    suspend fun checkNow() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val base = baseHttpUrl()
        if (base == null) {
            _state.value = UpdateState.Failed("Set the signaling server URL first")
            return
        }
        val info = try {
            checkForUpdate()
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(t.message ?: "Update check failed")
            return
        }
        _state.value = if (info == null) {
            UpdateState.UpToDate(installedVersionName)
        } else {
            UpdateState.Available(info)
        }
    }

    /** Downloads the APK in the background and reports progress. */
    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            val base = baseHttpUrl() ?: throw IllegalStateException("No server URL configured")
            val request = Request.Builder().url(base + info.apkUrl).build()
            val dir = context.getExternalFilesDir("updates") ?: context.filesDir
            val target = File(dir, "QuickVoiceDialer.apk")
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download failed (${resp.code})")
                val total = resp.body?.contentLength() ?: -1L
                val sink = target.outputStream()
                var written = 0L
                resp.body!!.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            _state.value = UpdateState.Downloading(info, written.toFloat() / total.toFloat())
                        }
                    }
                }
                sink.flush()
                sink.close()
            }
            _state.value = UpdateState.Available(info, downloaded = true)
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(t.message ?: "Download failed")
        }
    }

    /** Hands the downloaded APK to the system installer. */
    fun installDownloaded(): Boolean {
        val info = (_state.value as? UpdateState.Available)?.takeIf { it.downloaded } ?: return false
        val dir = context.getExternalFilesDir("updates") ?: context.filesDir
        val file = File(dir, "QuickVoiceDialer.apk")
        if (!file.exists()) return false
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }
}
