package com.quickvoice.desktop.signaling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * Desktop replacement for [FirebaseSignalingClient] on Android. Uses the Firebase
 * REST API (Identity Toolkit for anonymous auth, RTDB REST + SSE for signaling)
 * so the desktop app can call Android users without any Firebase SDK dependency.
 *
 * Protocol (identical to the Android client):
 *   - Anonymous auth → stable UID
 *   - /directory/{shortId} → firebaseUid
 *   - /devices/{uid} → displayName, online, lastSeen
 *   - /inbox/{uid}/{autoId} → signaling JSON, listened via SSE (child_added)
 */
class FirebaseRestSignalingClient(
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var myUserId = ""
    @Volatile private var myShortId = ""
    @Volatile private var authToken: String = ""
    @Volatile private var displayName: String = ""
    @Volatile private var sseThread: Thread? = null

    // ── public API ───────────────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        scope.launch { connectNow() }
    }

    fun register(name: String) {
        displayName = name
        if (myUserId.isNotEmpty() && authToken.isNotEmpty()) {
            scope.launch {
                rtdbPut("devices/$myUserId/displayName", JSONObject().put("value", name))
            }
        }
    }

    fun stop() {
        started = false
        sseThread?.interrupt()
        sseThread = null
        if (myUserId.isNotEmpty() && authToken.isNotEmpty()) {
            scope.launch { setPresence(false) }
        }
        authToken = ""
        myUserId = ""
    }

    /**
     * Send a signaling message. The [payload] must contain a "to" field with the target user's
     * short ID or Firebase UID. The message is written to /inbox/{resolvedTargetUid}/{autoId}.
     */
    fun send(type: String, payload: JSONObject) {
        val target = payload.optString("to", "")
        if (target.isBlank()) {
            System.err.println("[FirebaseRestSignaling] send() called without 'to' field for type=$type")
            return
        }
        scope.launch {
            try {
                val resolvedTarget = resolveTargetUserId(target)
                val message = JSONObject().apply {
                    put("type", type)
                    put("from", myUserId)
                    val keys = payload.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "to") put(key, payload.get(key))
                    }
                    put("ts", System.currentTimeMillis())
                }
                rtdbPost("inbox/$resolvedTarget", message)
            } catch (t: Throwable) {
                System.err.println("[FirebaseRestSignaling] Failed to send signal: ${t.message}")
            }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun connectNow() {
        try {
            val result = firebaseAnonymousAuth()
            myUserId = result.optString("localId", "")
            authToken = result.optString("idToken", "")
            if (myUserId.isBlank() || authToken.isBlank()) {
                _events.tryEmit(SignalEvent.SocketFailure("Firebase anonymous auth failed"))
                return
            }
            println("[FirebaseRestSignaling] Firebase anonymous uid: $myUserId")

            myShortId = resolveOrCreateShortId()
            setPresence(true)
            if (displayName.isNotEmpty()) {
                rtdbPut("devices/$myUserId/displayName", JSONObject().put("value", displayName))
            }
            listenInboxSse()
            _events.tryEmit(SignalEvent.SocketOpen(myUserId))
            _events.tryEmit(SignalEvent.Registered(myShortId, displayName.ifBlank { myShortId }))
        } catch (t: Throwable) {
            System.err.println("[FirebaseRestSignaling] Firebase connect failed: ${t.message}")
            _events.tryEmit(SignalEvent.SocketFailure(t.message ?: "Firebase connect failed"))
        }
    }

    // ── Firebase REST: anonymous auth ────────────────────────────────────────

    private suspend fun firebaseAnonymousAuth(): JSONObject {
        val body = JSONObject()
            .put("returnSecureToken", true)
            .toString()
        val req = Request.Builder()
            .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = okHttpClient.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException("Auth failed: ${resp.code} $text")
        return JSONObject(text)
    }

    // ── Firebase REST: RTDB operations ───────────────────────────────────────

    private suspend fun rtdbGet(path: String): JSONObject? {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url).get().build()
        val resp = okHttpClient.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (resp.code == 200 && text != "null" && text.isNotBlank()) {
            return try { JSONObject(text) } catch (_: Throwable) { null }
        }
        return null
    }

    private suspend fun rtdbGetString(path: String): String? {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url).get().build()
        val resp = okHttpClient.newCall(req).execute()
        val text = resp.body?.string().orEmpty()
        if (resp.code == 200 && text != "null" && text.isNotBlank()) {
            return try { text.trim('"') } catch (_: Throwable) { null }
        }
        return null
    }

    private suspend fun rtdbPut(path: String, value: JSONObject) {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val body = value.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).put(body).build()
        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            System.err.println("[FirebaseRestSignaling] RTDB PUT failed: ${resp.code} ${resp.body?.string()}")
        }
    }

    private suspend fun rtdbPost(path: String, value: JSONObject) {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val body = value.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            System.err.println("[FirebaseRestSignaling] RTDB POST failed: ${resp.code} ${resp.body?.string()}")
        }
    }

    private suspend fun rtdbDelete(path: String) {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url).delete().build()
        okHttpClient.newCall(req).execute()
    }

    // ── short numeric ID ─────────────────────────────────────────────────────

    private suspend fun resolveOrCreateShortId(): String {
        val existing = rtdbGetString("devices/$myUserId/shortId")
        if (!existing.isNullOrBlank() && existing.all { it.isDigit() }) {
            println("[FirebaseRestSignaling] Existing short ID: $existing")
            return existing
        }
        val newId = generateUniqueShortId()
        rtdbPut("directory/$newId", JSONObject().put("value", myUserId))
        rtdbPut("devices/$myUserId/shortId", JSONObject().put("value", newId))
        println("[FirebaseRestSignaling] Created short ID: $newId")
        return newId
    }

    private suspend fun generateUniqueShortId(): String {
        repeat(20) {
            val candidate = Random.nextLong(10_000_000L, 99_999_999L).toString()
            val existing = rtdbGetString("directory/$candidate")
            if (existing == null) return candidate
        }
        throw IllegalStateException("Could not generate unique short ID after 20 attempts")
    }

    private suspend fun resolveTargetUserId(id: String): String {
        val trimmed = id.trim()
        if (trimmed.length == 28 && trimmed.all { it.isLetterOrDigit() }) return trimmed
        return rtdbGetString("directory/$trimmed") ?: trimmed
    }

    // ── presence ─────────────────────────────────────────────────────────────

    private suspend fun setPresence(online: Boolean) {
        rtdbPut("devices/$myUserId/online", JSONObject().put("value", online))
        rtdbPut("devices/$myUserId/lastSeen", JSONObject().put("value", System.currentTimeMillis()))
    }

    // ── inbox listener via SSE ───────────────────────────────────────────────

    /**
     * Listen to /inbox/{uid} using Firebase RTDB Server-Sent Events (SSE).
     * The RTDB REST API supports `.json?shallow=true` for child_added events.
     * We poll using GET with `shallow=true` and track new child keys.
     */
    private fun listenInboxSse() {
        sseThread?.interrupt()
        sseThread = Thread({
            println("[FirebaseRestSignaling] Starting inbox SSE listener for $myUserId")
            val knownKeys = mutableSetOf<String>()
            var failCount = 0
            while (started && !Thread.currentThread().isInterrupted) {
                try {
                    val url = "$RTDB_URL/inbox/$myUserId.json?auth=$authToken&shallow=true"
                    val req = Request.Builder().url(url).get().build()
                    val resp = okHttpClient.newCall(req).execute()
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && text.isNotBlank() && text != "null") {
                        val obj = JSONObject(text)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key !in knownKeys) {
                                knownKeys.add(key)
                                val messageObj = obj.optJSONObject(key)
                                if (messageObj != null) {
                                    processMessage(messageObj)
                                    // Delete after processing
                                    scope.launch { rtdbDelete("inbox/$myUserId/$key") }
                                }
                            }
                        }
                        failCount = 0
                    } else if (!resp.isSuccessful && resp.code != 404) {
                        failCount++
                        if (failCount > 5) {
                            _events.tryEmit(SignalEvent.SocketFailure("SSE auth expired, reconnecting"))
                            scope.launch {
                                delay(3000)
                                if (started) connectNow()
                            }
                            return@Thread
                        }
                    } else {
                        failCount = 0
                    }
                    // Cleanup keys no longer in the response (already deleted)
                    val currentKeys = if (text.isNotBlank() && text != "null") {
                        try { JSONObject(text).keys().asSequence().toSet() } catch (_: Throwable) { emptySet() }
                    } else emptySet()
                    knownKeys.retainAll(currentKeys)

                    Thread.sleep(1000) // Poll every second
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    System.err.println("[FirebaseRestSignaling] SSE error: ${t.message}")
                    failCount++
                    if (failCount > 5) {
                        scope.launch {
                            delay(3000)
                            if (started) connectNow()
                        }
                        return@Thread
                    }
                    Thread.sleep(2000)
                }
            }
            println("[FirebaseRestSignaling] SSE listener stopped")
        }, "firebase-inbox-sse")
        sseThread?.isDaemon = true
        sseThread?.start()
    }

    // ── message processing ───────────────────────────────────────────────────

    private fun processMessage(obj: JSONObject) {
        try {
            when (val type = obj.optString("type")) {
                "call" -> _events.tryEmit(
                    SignalEvent.IncomingCall(
                        callId = obj.optString("callId"),
                        from = obj.optString("from"),
                        fromName = obj.optString("fromName", obj.optString("from")),
                        sdp = obj.optString("sdp"),
                    )
                )
                "answer" -> _events.tryEmit(
                    SignalEvent.RemoteAnswer(obj.optString("callId"), obj.optString("sdp"))
                )
                "offer" -> _events.tryEmit(
                    SignalEvent.RemoteOffer(obj.optString("callId"), obj.optString("sdp"))
                )
                "ice" -> _events.tryEmit(
                    SignalEvent.RemoteIce(
                        callId = obj.optString("callId"),
                        sdpMid = if (obj.has("sdpMid")) obj.optString("sdpMid") else null,
                        sdpMLineIndex = obj.optInt("sdpMLineIndex", 0),
                        candidate = obj.optString("candidate"),
                    )
                )
                "hangup" -> _events.tryEmit(SignalEvent.RemoteHangup(obj.optString("callId")))
                "decline" -> _events.tryEmit(SignalEvent.RemoteDecline(obj.optString("callId")))
                "presence" -> {
                    val target = obj.optString("to")
                    checkPresence(target)
                }
                "voicemessage" -> _events.tryEmit(
                    SignalEvent.VoiceMessageReceived(
                        com.quickvoice.core.model.VoiceMessage(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            fromUserId = obj.optString("from"),
                            fromName = obj.optString("fromName", ""),
                            toUserId = obj.optString("to"),
                            mediaBytes = Base64.getDecoder().decode(obj.optString("media")),
                            durationMs = obj.optLong("durationMs", 0L),
                            mimeType = obj.optString("mime", "audio/3gpp"),
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                )
                else -> println("[FirebaseRestSignaling] Unknown message type: $type")
            }
        } catch (t: Throwable) {
            System.err.println("[FirebaseRestSignaling] Failed to process signal: ${t.message}")
        }
    }

    private fun checkPresence(peerId: String) {
        scope.launch {
            val online = rtdbGetString("devices/$peerId/online")
            if (online != "true") {
                _events.tryEmit(SignalEvent.PeerOffline(peerId))
            }
        }
    }

    private companion object {
        const val API_KEY = "AIzaSyBlmwSX4KJBWNDrVkPNLJOaxYkCHqmBL4o"
        const val RTDB_URL = "https://call-39dc2-default-rtdb.firebaseio.com"
    }
}
