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
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * Desktop replacement for FirebaseSignalingClient on Android. Uses Firebase REST API
 * (Identity Toolkit for anonymous auth, RTDB REST + polling for signaling) so the
 * desktop app can call Android users without any Firebase SDK dependency.
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
    @Volatile private var authToken = ""
    @Volatile private var displayName = ""
    @Volatile private var sseThread: Thread? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { connectNow() }
    }

    fun register(name: String) {
        displayName = name
        if (myUserId.isNotEmpty() && authToken.isNotEmpty()) {
            scope.launch {
                rtdbPutRaw("devices/$myUserId/displayName", "\"${name.replace("\"", "\\\"")}\"")
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

    fun send(type: String, payload: JSONObject) {
        val target = payload.optString("to", "")
        if (target.isBlank()) {
            System.err.println("[FirebaseRest] send() without 'to' for type=$type")
            return
        }
        scope.launch {
            try {
                val resolvedTarget = resolveTargetUserId(target)
                val message = JSONObject().apply {
                    put("type", type)
                    put("from", myUserId)
                    put("fromName", displayName.ifBlank { myShortId })
                    val keys = payload.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "to") put(key, payload.get(key))
                    }
                    put("ts", System.currentTimeMillis())
                }
                rtdbPost("inbox/$resolvedTarget", message.toString())
            } catch (t: Throwable) {
                System.err.println("[FirebaseRest] Failed to send: ${t.message}")
            }
        }
    }

    // ── connect ──────────────────────────────────────────────────────────────

    private suspend fun connectNow() {
        try {
            val result = firebaseAnonymousAuth()
            myUserId = result.optString("localId", "")
            authToken = result.optString("idToken", "")
            if (myUserId.isBlank() || authToken.isBlank()) {
                _events.tryEmit(SignalEvent.SocketFailure("Firebase anonymous auth failed"))
                return
            }
            println("[FirebaseRest] Firebase uid: $myUserId")

            myShortId = resolveOrCreateShortId()
            setPresence(true)
            if (displayName.isNotEmpty()) {
                rtdbPutRaw("devices/$myUserId/displayName", "\"${displayName.replace("\"", "\\\"")}\"")
            }
            startInboxPolling()
            _events.tryEmit(SignalEvent.SocketOpen(myUserId))
            _events.tryEmit(SignalEvent.Registered(myShortId, displayName.ifBlank { myShortId }))
        } catch (t: Throwable) {
            System.err.println("[FirebaseRest] Connect failed: ${t.message}")
            _events.tryEmit(SignalEvent.SocketFailure(t.message ?: "Firebase connect failed"))
        }
    }

    // ── Firebase REST: auth ──────────────────────────────────────────────────

    private suspend fun firebaseAnonymousAuth(): JSONObject {
        val body = JSONObject().put("returnSecureToken", true).toString()
        val req = Request.Builder()
            .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = okHttpClient.newCall(req).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("Auth failed: ${it.code} $text")
            return JSONObject(text)
        }
    }

    // ── Firebase REST: RTDB ──────────────────────────────────────────────────

    /** Write a raw JSON value (string, number, boolean, object) to RTDB path. */
    private suspend fun rtdbPutRaw(path: String, rawJson: String) {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url)
            .put(rawJson.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = okHttpClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) {
                System.err.println("[FirebaseRest] PUT failed $path: ${it.code} ${it.body?.string()}")
            }
        }
    }

    /** Write a JSON object to RTDB path. */
    private suspend fun rtdbPutObject(path: String, json: String) = rtdbPutRaw(path, json)

    /** POST (push) a JSON object to an RTDB collection path. Returns push key. */
    private suspend fun rtdbPost(path: String, json: String): String? {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = okHttpClient.newCall(req).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                System.err.println("[FirebaseRest] POST failed $path: ${it.code} $text")
                return null
            }
            val obj = try { JSONObject(text) } catch (_: Throwable) { null }
            return obj?.optString("name")
        }
    }

    private suspend fun rtdbGetString(path: String): String? {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url).get().build()
        val resp = okHttpClient.newCall(req).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 200 && text.isNotBlank() && text != "null") {
                return try {
                    if (text.startsWith("\"")) text.trim('"') else text
                } catch (_: Throwable) { null }
            }
            return null
        }
    }

    private suspend fun rtdbDelete(path: String) {
        val url = "$RTDB_URL/$path.json?auth=$authToken"
        val req = Request.Builder().url(url).delete().build()
        okHttpClient.newCall(req).execute().use { }
    }

    // ── short numeric ID ─────────────────────────────────────────────────────

    private suspend fun resolveOrCreateShortId(): String {
        val existing = rtdbGetString("devices/$myUserId/shortId")
        if (!existing.isNullOrBlank() && existing.all { it.isDigit() }) {
            println("[FirebaseRest] Existing short ID: $existing")
            return existing
        }
        val newId = generateUniqueShortId()
        rtdbPutRaw("directory/$newId", "\"$myUserId\"")
        rtdbPutRaw("devices/$myUserId/shortId", "\"$newId\"")
        println("[FirebaseRest] Created short ID: $newId")
        return newId
    }

    private suspend fun generateUniqueShortId(): String {
        repeat(20) {
            val candidate = Random.nextLong(10_000_000L, 99_999_999L).toString()
            if (rtdbGetString("directory/$candidate") == null) return candidate
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
        rtdbPutRaw("devices/$myUserId/online", if (online) "true" else "false")
        rtdbPutRaw("devices/$myUserId/lastSeen", System.currentTimeMillis().toString())
    }

    // ── inbox polling ────────────────────────────────────────────────────────

    private fun startInboxPolling() {
        sseThread?.interrupt()
        sseThread = Thread({
            println("[FirebaseRest] Inbox polling started for $myUserId")
            val knownKeys = mutableSetOf<String>()
            var failCount = 0
            while (started && !Thread.currentThread().isInterrupted) {
                try {
                    val url = "$RTDB_URL/inbox/$myUserId.json?auth=$authToken"
                    val req = Request.Builder().url(url).get().build()
                    val resp = okHttpClient.newCall(req).execute()
                    resp.use { r ->
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful && text.isNotBlank() && text != "null") {
                            val obj = JSONObject(text)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (key !in knownKeys) {
                                    knownKeys.add(key)
                                    val messageObj = obj.optJSONObject(key)
                                    if (messageObj != null) {
                                        processMessage(messageObj)
                                        scope.launch { rtdbDelete("inbox/$myUserId/$key") }
                                    }
                                }
                            }
                            knownKeys.retainAll(obj.keys().asSequence().toSet())
                            failCount = 0
                        } else if (!r.isSuccessful && r.code != 404) {
                            failCount++
                            if (failCount > 5) {
                                _events.tryEmit(SignalEvent.SocketFailure("Auth expired, reconnecting"))
                                scope.launch { delay(3000); if (started) connectNow() }
                                return@Thread
                            }
                        } else {
                            failCount = 0
                        }
                    }
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    System.err.println("[FirebaseRest] Poll error: ${t.message}")
                    failCount++
                    if (failCount > 5) {
                        scope.launch { delay(3000); if (started) connectNow() }
                        return@Thread
                    }
                    Thread.sleep(2000)
                }
            }
            println("[FirebaseRest] Polling stopped")
        }, "firebase-inbox-poll")
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
                        mode = obj.optString("mode", "call"),
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
                "presence" -> checkPresence(obj.optString("to"))
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
                else -> println("[FirebaseRest] Unknown type: $type")
            }
        } catch (t: Throwable) {
            System.err.println("[FirebaseRest] Process error: ${t.message}")
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
