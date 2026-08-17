package com.quickvoice.feature.quickvoice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickvoice.core.quickvoice.QuickVoiceController
import com.quickvoice.core.quickvoice.QuickVoiceStatus

/**
 * Press-and-hold Quick Voice button. Shows a progress ring while recording and a
 * pulse animation, giving the fastest possible one-hand "record a short message"
 * experience without any extra screens.
 */
@Composable
fun QuickVoiceButton(
    recording: Boolean,
    elapsedMs: Long,
    maxDurationMs: Long,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit = {},
    onPressEnd: () -> Unit = {},
) {
    val ringProgress by animateFloatAsState(
        targetValue = if (maxDurationMs > 0) (elapsedMs.toFloat() / maxDurationMs.toFloat()).coerceIn(0f, 1f) else 0f,
        label = "quickVoiceProgress",
    )
    val pulseScale by animateFloatAsState(
        targetValue = if (recording) 1.08f else 1f,
        label = "quickVoicePulse",
    )
    val isRecording by rememberUpdatedState(recording)

    Box(
        modifier = modifier
            .requiredSize(112.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        if (isRecording) onPressEnd()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Progress ring (visible while recording).
        val ringColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.requiredSize(112.dp)) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * ringProgress,
                useCenter = false,
                style = stroke,
            )
        }

        // Glow pulse behind the mic.
        Box(
            modifier = Modifier
                .requiredSize(96.dp * pulseScale)
                .clip(CircleShape)
                .background(
                    if (recording) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.requiredSize(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Quick Voice",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}

/** Human-readable status line shown above the Quick Voice button. */
@Composable
fun QuickVoiceStatusLine(status: QuickVoiceStatus, modifier: Modifier = Modifier) {
    val text = when (status) {
        is QuickVoiceStatus.Idle -> "Quick Voice is on"
        is QuickVoiceStatus.Armed -> status.reason
        is QuickVoiceStatus.Recording -> "Recording…"
        is QuickVoiceStatus.Sending -> "Sending…"
        is QuickVoiceStatus.Sent -> status.detail
        is QuickVoiceStatus.Error -> status.message
    }
    Text(
        text = text,
        modifier = modifier,
        color = if (status is QuickVoiceStatus.Error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

/**
 * Binds to the [QuickVoiceController] and renders the full hold-to-talk control:
 * status text + button + duration hint. One component, zero navigation.
 */
@Composable
fun QuickVoiceHoldToTalk(
    controller: QuickVoiceController,
    modifier: Modifier = Modifier,
) {
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    if (!uiState.enabled) return

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        QuickVoiceStatusLine(uiState.status, modifier = Modifier.padding(bottom = 8.dp))
        QuickVoiceButton(
            recording = uiState.isRecording,
            elapsedMs = (uiState.status as? QuickVoiceStatus.Recording)?.elapsedMs ?: 0L,
            maxDurationMs = uiState.maxDurationMs,
            onPressStart = { controller.startHoldToTalk() },
            onPressEnd = { controller.stopHoldToTalk() },
        )
        Text(
            text = "Hold to talk · up to ${uiState.maxDurationMs / 1000} sec",
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
