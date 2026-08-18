package com.quickvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.model.CallState
import com.quickvoice.desktop.AppState
import com.quickvoice.desktop.voip.IncomingCallInfo
import com.quickvoice.desktop.voip.SignalingState
import kotlinx.coroutines.delay

@Composable
fun App(appState: AppState) {
    MaterialTheme {
        val session by appState.activeSession.collectAsState()
        val incoming by appState.incomingCall.collectAsState()
        val error by appState.lastError.collectAsState()
        var showSettings by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                session != null -> CallScreen(appState, session!!)
                showSettings -> SettingsScreen(appState, onBack = { showSettings = false })
                else -> HomeScreen(appState, onOpenSettings = { showSettings = true })
            }

            incoming?.let { info ->
                IncomingCallDialog(
                    info = info,
                    onAnswer = { appState.acceptIncoming() },
                    onDecline = { appState.declineIncoming() },
                )
            }

            error?.let { message ->
                Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    ErrorBanner(message, onDismiss = { appState.clearError() })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(appState: AppState, onOpenSettings: () -> Unit) {
    val sigState by appState.signalingState.collectAsState()
    val myUserId by appState.userId.collectAsState()
    var peer by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("QuickVoice Dialer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        StatusChip(sigState)
        Spacer(Modifier.height(28.dp))
        Text("Your number", style = MaterialTheme.typography.titleSmall)
        Text(
            myUserId.ifBlank { "(getting a number…)" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = peer,
            onValueChange = { peer = it },
            label = { Text("Number to call") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { appState.call(peer) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Call", fontSize = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Settings")
        }
    }
}

@Composable
private fun SettingsScreen(appState: AppState, onBack: () -> Unit) {
    val sigState by appState.signalingState.collectAsState()
    val userId by appState.userId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        StatusChip(sigState)
        if (userId.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Registered as: $userId", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = appState.user,
            onValueChange = { appState.user = it },
            label = { Text("My number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = appState.displayName,
            onValueChange = { appState.displayName = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { appState.saveSettingsAndConnect() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Save & Connect", fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
private fun CallScreen(appState: AppState, session: CallSession) {
    val active = session.state == CallState.ACTIVE
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        while (active) {
            delay(1000)
            seconds++
        }
    }

    val stateText = when (session.state) {
        CallState.RINGING -> "Ringing…"
        CallState.CONNECTING -> "Connecting…"
        CallState.ACTIVE -> formatTimer(seconds)
        else -> "Call ended"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            session.displayName.ifBlank { session.number },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(session.number, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Text(stateText, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            OutlinedIconButton(onClick = { appState.toggleMute() }) {
                Icon(
                    if (session.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Mute",
                )
            }
            Button(
                onClick = { appState.hangup() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.height(52.dp),
            ) {
                Text("Hang up", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun IncomingCallDialog(info: IncomingCallInfo, onAnswer: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Incoming call") },
        text = {
            Column {
                Text(info.fromName.ifBlank { info.fromUserId }, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(info.fromUserId, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = onAnswer) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Answer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Decline") }
        },
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Error, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun StatusChip(state: SignalingState) {
    val (label, color) = when (state) {
        SignalingState.REGISTERED -> "Registered (ready)" to Color(0xFF2E7D32)
        SignalingState.CONNECTING -> "Connecting…" to Color(0xFFF57F17)
        SignalingState.DISCONNECTED -> "Offline" to Color(0xFFC62828)
    }
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatTimer(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
