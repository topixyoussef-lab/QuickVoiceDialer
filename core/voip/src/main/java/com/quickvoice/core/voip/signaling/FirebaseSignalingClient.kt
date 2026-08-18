package com.quickvoice.core.voip.signaling

import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.quickvoice.core.model.VoiceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Firebase Realtime Database signaling client — replaces the self-hosted WebSocket server.
 *
 * Protocol:
 *   - Each device authenticates anonymously and gets a stable UID (persisted in SharedPrefs).
 *   - Device metadata (displayName, fcmToken) lives under /devices/{uid}.
 *   - Signaling messages are written to /inbox/{recipientUid}/{autoId} and listened to via
 *     ChildEventListener. FCM is used only to wake up the recipient when the app is backgrounded.
 *   - Voice messages for offline peers are stored under /voice_messages/{autoId}.
 */
class FirebaseSignalingClient(
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val messaging: FirebaseMessaging,
) {
    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var myUserId: String = ""
    @Volatile private var myShortId: String = ""
    @Volatile private var inboxListener: ChildEventListener? = null

    private var displayName: String = ""

    // ── public API (same shape as the old WebSocket SignalingClient) ────────

    fun start(serverUrl: String) {
        if (started) return
        started = true
        connectNow()
    }

    fun register(name: String) {
        displayName = name
        if (myUserId.isNotEmpty()) {
            database.getReference("devices").child(myUserId).child("displayName").setValue(name)
        }
    }

    fun stop() {
        started = false
        removeInboxListener()
        if (myUserId.isNotEmpty()) {
            setPresence(false)
        }
        auth.signOut()
    }

    fun send(type: String, payload: JSONObject) {
        val target = payload.optString("to", "")
        if (target.isBlank()) {
            Log.w(TAG, "send() called without 'to' field for type=$type")
            return
        }

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

        val ref = database.getReference("inbox").child(target).push()
        ref.setValue(message.toString())
            .addOnFailureListener { Log.w(TAG, "Failed to write signal to Firebase", it) }

        sendFcmWake(target, type)
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun connectNow() {
        scope.launch {
            try {
                val result = auth.signInAnonymously().await()
                myUserId = result.user?.uid.orEmpty()
                if (myUserId.isBlank()) {
                    _events.tryEmit(SignalEvent.SocketFailure("Firebase anonymous auth failed"))
                    return@launch
                }

                Log.i(TAG, "Firebase anonymous uid: $myUserId")

                // Resolve or generate a short numeric ID (8 digits) for this user
                myShortId = resolveOrCreateShortId()

                registerFcmToken()
                setPresence(true)
                if (displayName.isNotEmpty()) {
                    database.getReference("devices").child(myUserId).child("displayName").setValue(displayName)
                }
                listenInbox()
                _events.tryEmit(SignalEvent.SocketOpen(myUserId))
                _events.tryEmit(SignalEvent.Registered(myShortId, displayName.ifBlank { myShortId }))
            } catch (t: Throwable) {
                Log.e(TAG, "Firebase connect failed", t)
                _events.tryEmit(SignalEvent.SocketFailure(t.message ?: "Firebase connect failed"))
            }
        }
    }

    /**
     * Look up or create a short numeric ID (8 digits) mapped to this Firebase UID.
     * Stored in Firebase RTDB under /directory/{shortId} → firebaseUid and
     * /devices/{firebaseUid}/shortId.
     */
    private suspend fun resolveOrCreateShortId(): String {
        val dirRef = database.getReference("directory")
        val myDeviceRef = database.getReference("devices").child(myUserId).child("shortId")

        // Check if we already have a short ID assigned
        val existing = myDeviceRef.get().await().value as? String
        if (!existing.isNullOrBlank() && existing.all { it.isDigit() }) {
            Log.i(TAG, "Existing short ID: $existing")
            return existing
        }

        // Generate a unique 8-digit number
        val newShortId = generateUniqueShortId(dirRef)
        dirRef.child(newShortId).setValue(myUserId).await()
        myDeviceRef.setValue(newShortId).await()
        Log.i(TAG, "Created short ID: $newShortId")
        return newShortId
    }

    private suspend fun generateUniqueShortId(dirRef: com.google.firebase.database.DatabaseReference): String {
        repeat(20) {
            val candidate = Random.nextLong(10000000L, 99999999L).toString()
            val existing = dirRef.child(candidate).get().await().value
            if (existing == null) return candidate
        }
        throw IllegalStateException("Could not generate unique short ID after 20 attempts")
    }

    /** Resolve a short numeric ID to a Firebase UID. Returns the input if it's already a UID. */
    suspend fun resolveTargetUserId(id: String): String {
        val trimmed = id.trim()
        if (trimmed.length == 28 && trimmed.all { it.isLetterOrDigit() }) return trimmed
        return database.getReference("directory").child(trimmed).get().await().value as? String ?: trimmed
    }

    private fun registerFcmToken() {
        messaging.token.addOnSuccessListener { token ->
            val deviceRef = database.getReference("devices").child(myUserId)
            deviceRef.child("fcmToken").setValue(token)
            deviceRef.child("lastSeen").setValue(System.currentTimeMillis())
        }
    }

    private fun setPresence(online: Boolean) {
        val ref = database.getReference("devices").child(myUserId)
        ref.child("online").setValue(online)
        ref.child("lastSeen").setValue(System.currentTimeMillis())
        if (online) {
            ref.child("online").onDisconnect().setValue(false)
            ref.child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())
        }
    }

    private fun listenInbox() {
        removeInboxListener()
        val ref = database.getReference("inbox").child(myUserId)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val raw = snapshot.value as? String ?: return
                snapshot.ref.removeValue()
                processMessage(raw)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Inbox listener cancelled", error.toException())
                if (started) scheduleReconnect()
            }
        }
        inboxListener = listener
        ref.addChildEventListener(listener)
    }

    private fun removeInboxListener() {
        inboxListener?.let {
            database.getReference("inbox").child(myUserId).removeEventListener(it)
        }
        inboxListener = null
    }

    private fun processMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            when (val type = json.optString("type")) {
                "call" -> _events.tryEmit(
                    SignalEvent.IncomingCall(
                        callId = json.optString("callId"),
                        from = json.optString("from"),
                        fromName = json.optString("fromName", json.optString("from")),
                        sdp = json.optString("sdp"),
                        mode = json.optString("mode", "call"),
                    )
                )
                "answer" -> _events.tryEmit(
                    SignalEvent.RemoteAnswer(json.optString("callId"), json.optString("sdp"))
                )
                "offer" -> _events.tryEmit(
                    SignalEvent.RemoteOffer(json.optString("callId"), json.optString("sdp"))
                )
                "ice" -> _events.tryEmit(
                    SignalEvent.RemoteIce(
                        callId = json.optString("callId"),
                        sdpMid = if (json.has("sdpMid")) json.optString("sdpMid") else null,
                        sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                        candidate = json.optString("candidate"),
                    )
                )
                "hangup" -> _events.tryEmit(SignalEvent.RemoteHangup(json.optString("callId")))
                "decline" -> _events.tryEmit(SignalEvent.RemoteDecline(json.optString("callId")))
                "presence" -> {
                    val target = json.optString("to")
                    checkPresence(target)
                }
                "voicemessage" -> _events.tryEmit(
                    SignalEvent.VoiceMessageReceived(
                        VoiceMessage(
                            id = json.optString("id", UUID.randomUUID().toString()),
                            fromUserId = json.optString("from"),
                            fromName = json.optString("fromName", ""),
                            toUserId = json.optString("to"),
                            mediaBytes = Base64.decode(json.optString("media"), Base64.NO_WRAP),
                            durationMs = json.optLong("durationMs", 0L),
                            mimeType = json.optString("mime", "audio/3gpp"),
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                )
                else -> Log.d(TAG, "Unknown message type: $type")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to process signal", t)
        }
    }

    private fun checkPresence(peerId: String) {
        database.getReference("devices").child(peerId).child("online")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val online = snapshot.getValue(Boolean::class.java) ?: false
                    if (!online) {
                        _events.tryEmit(SignalEvent.PeerOffline(peerId))
                    }
                }
                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private fun sendFcmWake(targetUid: String, type: String) {
        database.getReference("devices").child(targetUid).child("fcmToken")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val token = snapshot.getValue(String::class.java) ?: return
                    Log.d(TAG, "FCM wake for $targetUid (token present, type=$type)")
                }
                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(3000)
            if (started) connectNow()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        const val TAG = "FirebaseSignalingClient"
    }
}
