package com.quickvoice.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val info: UpdateInfo, val downloaded: Boolean = false) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for the latest APK and handles download + install.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val REPO_OWNER = "topixyoussef-lab"
        const val REPO_NAME = "QuickVoiceDialer"
        const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

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

    fun canRequestPackageInstalls(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.trimStart('v', 'V').split(".")
        if (parts.isEmpty()) return 0
        var code = 0
        for (p in parts) {
            val segment = p.takeWhile { it.isDigit() }.toIntOrNull() ?: break
            code = code * 1000 + segment
        }
        return code
    }

    private fun findApkUrl(releaseJson: JSONObject): String? {
        val assets: JSONArray = releaseJson.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url", null)
            }
        }
        return null
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_RELEASES_URL)
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub responded HTTP ${resp.code}")
            resp.body?.string()
        } ?: throw IOException("Empty response from GitHub")
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("Invalid JSON from GitHub")
        }
        val tagName = json.optString("tag_name", "")
        if (tagName.isBlank()) throw IOException("Release has no tag")
        val versionCode = parseVersionCode(tagName)
        val apkUrl = findApkUrl(json) ?: throw IOException("No APK found in release")
        val info = UpdateInfo(
            versionName = tagName,
            versionCode = versionCode,
            apkUrl = apkUrl,
        )
        if (info.versionCode > installedVersionCode) info else null
    }

    suspend fun checkNow() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
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

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            val request = Request.Builder().url(info.apkUrl).build()
            val dir = context.getExternalFilesDir("updates") ?: context.filesDir
            val target = File(dir, "QuickVoiceDialer.apk")
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
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
