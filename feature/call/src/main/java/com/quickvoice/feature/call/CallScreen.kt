package com.quickvoice.feature.call

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickvoice.core.design.components.InitialsAvatar
import com.quickvoice.core.design.components.RoundActionButton
import com.quickvoice.core.model.AudioRoute
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallSession
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.feature.quickvoice.QuickVoiceHoldToTalk

@Composable
fun CallRoute(viewModel: CallViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val sessionValue = session ?: return
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    CallScreen(
        session = sessionValue,
        recording = recording,
        onEnd = { viewModel.endCall() },
        onAnswer = { viewModel.answer() },
        onReject = { viewModel.reject() },
        onToggleMute = { viewModel.toggleMute() },
        onSetMicMuted = { viewModel.setMicMuted(it) },
        onToggleSpeaker = { viewModel.toggleSpeaker() },
        onToggleRecording = { if (recording) viewModel.stopRecording() else viewModel.startRecording() },
        quickVoice = viewModel.quickVoiceController,
    )
}

@Composable
private fun CallScreen(
    session: CallSession,
    recording: Boolean,
    onEnd: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onToggleMute: () -> Unit,
    onSetMicMuted: (Boolean) -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleRecording: () -> Unit,
    quickVoice: com.quickvoice.core.quickvoice.QuickVoiceController,
) {
    var elapsedSeconds by remember(session.id) { mutableIntStateOf(0) }

    LaunchedEffect(session.id, session.state) {
        if (session.state == CallState.ACTIVE) {
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - session.startedAtEpochMillis) / 1000L).toInt()
                kotlinx.coroutines.delay(1_000)
            }
        } else {
            elapsedSeconds = 0
        }
    }

    val isIncoming = session.direction == CallDirection.INCOMING && session.state == CallState.RINGING
    val isIntercomCallee = session.isIntercom && session.direction == CallDirection.INCOMING

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            InitialsAvatar(name = session.displayName, size = 120.dp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = session.displayName,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (session.isIntercom) {
                Text(
                    text = "Intercom",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    text = session.number.ifBlank { session.voipPeerId.orEmpty() },
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stateText(session, elapsedSeconds),
                fontSize = 15.sp,
                color = when (session.state) {
                    CallState.ACTIVE -> MaterialTheme.colorScheme.primary
                    CallState.RINGING -> MaterialTheme.colorScheme.tertiary
                    CallState.DISCONNECTED, CallState.MISSED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (recording) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "REC",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            if (isIncoming) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundActionButton(
                        icon = Icons.Filled.Phone,
                        label = "Answer",
                        active = true,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(96.dp),
                        onClick = onAnswer,
                    )
                    RoundActionButton(
                        icon = Icons.Filled.PhoneDisabled,
                        label = "Decline",
                        active = false,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(96.dp),
                        onClick = onReject,
                    )
                }
            } else if (isIntercomCallee) {
                IntercomTalkButton(
                    isMuted = session.isMicMuted,
                    onPress = { onSetMicMuted(false) },
                    onRelease = { onSetMicMuted(true) },
                )
                Spacer(modifier = Modifier.height(20.dp))
                RoundActionButton(
                    icon = Icons.Filled.Phone,
                    label = "End",
                    active = true,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onEnd,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (session.type == CallType.VOIP && session.state == CallState.ACTIVE) {
                        RoundActionButton(
                            icon = Icons.Filled.FiberManualRecord,
                            label = if (recording) "Stop" else "Record",
                            active = recording,
                            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            onClick = onToggleRecording,
                        )
                    }
                    RoundActionButton(
                        icon = Icons.Filled.VolumeUp,
                        label = "Speaker",
                        active = session.audioRoute == AudioRoute.SPEAKER,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onToggleSpeaker,
                    )
                    RoundActionButton(
                        icon = if (session.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = "Mute",
                        active = session.isMicMuted,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onToggleMute,
                    )
                    RoundActionButton(
                        icon = Icons.Filled.Phone,
                        label = "End",
                        active = true,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onEnd,
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (!isIncoming && !session.isIntercom) {
                QuickVoiceHoldToTalk(controller = quickVoice)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun IntercomTalkButton(
    isMuted: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val talking = !isMuted
    val pressHandler by rememberUpdatedState(onPress)
    val releaseHandler by rememberUpdatedState(onRelease)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (talking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .size(160.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressHandler()
                        tryAwaitRelease()
                        releaseHandler()
                    },
                )
            },
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = if (talking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (talking) "Listening…" else "Hold to Talk",
                color = if (talking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun stateText(session: CallSession, elapsedSeconds: Int): String {
    val base = when (session.state) {
        CallState.CONNECTING -> "Connecting…"
        CallState.RINGING -> "Ringing…"
        CallState.ACTIVE -> formatDuration(elapsedSeconds)
        CallState.HOLD -> "On hold"
        CallState.DISCONNECTING -> "Ending…"
        CallState.DISCONNECTED -> "Call ended"
        CallState.MISSED -> "Missed"
        else -> ""
    }
    if (session.isIntercom && session.state == CallState.ACTIVE) {
        return if (session.direction == CallDirection.INCOMING) {
            "Intercom — hold Talk to reply"
        } else {
            "Intercom — speaking…"
        }
    }
    return base
}

private fun formatDuration(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
