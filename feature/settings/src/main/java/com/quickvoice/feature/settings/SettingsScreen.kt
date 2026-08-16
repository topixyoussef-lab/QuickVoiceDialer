package com.quickvoice.feature.settings

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickvoice.core.model.CallType
import com.quickvoice.core.update.UpdateState
import com.quickvoice.core.voip.model.SignalingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshDialerStatus()
    }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
        }
        viewModel.onRingtonePicked(uri)
    }

    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.consumeNotice()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader("Quick Voice") }
            item {
                SettingsCard {
                    SwitchRow(
                        icon = Icons.Filled.Mic,
                        title = "Quick Voice Mode",
                        subtitle = "Record a short message during a call by holding the mic button.",
                        checked = uiState.quickVoice.enabled,
                        onChecked = viewModel::setQuickVoiceEnabled,
                    )
                    SliderRow(
                        title = "Max message length",
                        valueText = "${uiState.quickVoice.maxMessageDurationMs / 1000}s",
                        value = uiState.quickVoice.maxMessageDurationMs.toFloat(),
                        valueRange = 2_000f..5_000f,
                        steps = 2,
                        onChange = { viewModel.setMaxDurationMs(it.toLong()) },
                    )
                    SliderRow(
                        title = "Auto-arm after no answer",
                        valueText = "${uiState.quickVoice.autoActivateAfterMs / 1000}s",
                        value = uiState.quickVoice.autoActivateAfterMs.toFloat(),
                        valueRange = 5_000f..30_000f,
                        steps = 4,
                        onChange = { viewModel.setAutoActivateMs(it.toLong()) },
                    )
                    SwitchRow(
                        icon = null,
                        title = "Auto speaker",
                        subtitle = "Turn the loudspeaker on automatically when Quick Voice arms.",
                        checked = uiState.quickVoice.autoEnableSpeaker,
                        onChecked = viewModel::setAutoSpeaker,
                    )
                }
            }

            item { SectionHeader("Audio & calls") }
            item {
                SettingsCard {
                    SwitchRow(
                        icon = Icons.Filled.GraphicEq,
                        title = "Start calls on speaker",
                        subtitle = "For SIM calls this is passed to the system dialer.",
                        checked = uiState.startWithSpeaker,
                        onChecked = viewModel::setStartWithSpeaker,
                    )
                    Text(
                        "Incoming ringtone",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        uiState.ringtoneName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = {
                            if (uiState.playingRingtone) viewModel.stopRingtonePreview()
                            else viewModel.previewRingtone()
                        }) {
                            Text(if (uiState.playingRingtone) "Stop" else "Preview")
                        }
                        Button(onClick = { ringtoneLauncher.launch(viewModel.ringtonePickerIntent()) }) {
                            Text("Change…")
                        }
                        TextButton(onClick = { viewModel.setRingtoneUri(SILENT_RINGTONE) }) {
                            Text("Silent", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        "Default uses the bundled QuickVoice ringtone; you can also pick one from your device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Default call type",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(
                            selected = uiState.defaultCallType == CallType.SIM,
                            onClick = { viewModel.setDefaultCallType(CallType.SIM) },
                            label = { Text("SIM") },
                        )
                        FilterChip(
                            selected = uiState.defaultCallType == CallType.VOIP,
                            onClick = { viewModel.setDefaultCallType(CallType.VOIP) },
                            label = { Text("Wi-Fi") },
                        )
                    }
                }
            }

            item { SectionHeader("Call recordings") }
            item {
                SettingsCard {
                    if (uiState.recordings.isEmpty()) {
                        Text(
                            "No recordings yet. During a VoIP call, tap Record in the call screen.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        uiState.recordings.forEach { recording ->
                            RecordingRow(
                                recording = recording,
                                playing = uiState.playingRecordingPath == recording.path,
                                onPlay = { viewModel.playRecording(recording) },
                                onDelete = { viewModel.deleteRecording(recording) },
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Wi-Fi calls") }
            item {
                SettingsCard {
                    VoipTextField(
                        label = "Signaling server (WebSocket)",
                        value = uiState.voipServerUrl,
                        userId = uiState.voipUserId,
                        displayName = uiState.voipDisplayName,
                        placeholder = "wss://your-public-server:8080/signaling",
                        turnUrl = uiState.voipTurnUrl,
                        turnUsername = uiState.voipTurnUsername,
                        turnPassword = uiState.voipTurnPassword,
                        onSave = { saved ->
                            viewModel.saveVoipSettings(
                                url = saved.first,
                                userId = saved.second,
                                displayName = saved.third,
                                turnUrl = saved.fourth,
                                turnUsername = saved.fifth,
                                turnPassword = saved.sixth,
                            )
                        },
                    )
                    Text(
                        "For calls between different networks, use a public server URL and fill the TURN server (e.g. coturn). On the same Wi-Fi, a local ws:// IP and no TURN is enough.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Server: ${uiState.signalingState.label()}",
                        fontWeight = FontWeight.Medium,
                        color = when (uiState.signalingState) {
                            SignalingState.REGISTERED -> MaterialTheme.colorScheme.primary
                            SignalingState.DISCONNECTED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = viewModel::connectVoip) {
                            Text(if (uiState.signalingState == SignalingState.REGISTERED) "Reconnect" else "Connect")
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (uiState.signalingState != SignalingState.DISCONNECTED) {
                            OutlinedButton(onClick = viewModel::disconnectVoip) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Background") }
            item {
                SettingsCard {
                    SwitchRow(
                        icon = Icons.Filled.Wifi,
                        title = "Keep running in background",
                        subtitle = "Stay connected to receive Wi-Fi calls and voice messages while the app is closed.",
                        checked = uiState.backgroundServiceEnabled,
                        onChecked = viewModel::setBackgroundServiceEnabled,
                    )
                    Text(
                        text = when {
                            !uiState.backgroundServiceEnabled ->
                                "Background keep-alive is off. Wi-Fi calls only work while the app is open."
                            uiState.backgroundServiceRunning ->
                                "Background service is running (low-priority notification)."
                            else ->
                                "Background service will start the next time you open the app."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item { SectionHeader("App update") }
            item {
                SettingsCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Installed version: ${uiState.installedVersion}", fontSize = 14.sp)
                            Text(
                                text = uiState.updateState.statusText(),
                                fontSize = 13.sp,
                                color = when (uiState.updateState) {
                                    is UpdateState.UpToDate -> MaterialTheme.colorScheme.primary
                                    is UpdateState.Failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (uiState.updateState is UpdateState.Downloading) {
                        LinearProgressIndicator(
                            progress = { (uiState.updateState as UpdateState.Downloading).progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        OutlinedButton(onClick = viewModel::checkForUpdates) {
                            Text("Check now")
                        }
                        val available = uiState.updateState as? UpdateState.Available
                        if (available != null && !available.downloaded) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(onClick = viewModel::downloadUpdate) {
                                Text("Download v${available.info.versionName}")
                            }
                        }
                        if (available != null && available.downloaded) {
                            Spacer(modifier = Modifier.width(10.dp))
                            if (uiState.canInstallPackages) {
                                Button(onClick = viewModel::installUpdate) {
                                    Text("Install v${available.info.versionName}")
                                }
                            } else {
                                Button(onClick = { startUnknownSources(context, viewModel.openInstallUnknownAppsSettings()) }) {
                                    Text("Allow installs & install")
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Permissions") }
            item {
                SettingsCard {
                    uiState.permissions.forEach { row ->
                        PermissionRowView(
                            row = row,
                            onClick = {
                                val missing = viewModel.missingPermissions()
                                if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                            },
                        )
                    }
                }
            }

            item { SectionHeader("Phone app") }
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("QuickVoice as phone app", fontWeight = FontWeight.Medium)
                            Text(
                                text = if (uiState.isDefaultDialer) {
                                    "Active — full call control available"
                                } else {
                                    "Required for in-app call screen, speaker auto-on and call state during SIM calls."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!uiState.isDefaultDialer) {
                        Button(
                            onClick = {
                                viewModel.requestDialerRoleIntent()?.let { roleLauncher.launch(it) }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Make default phone app")
                        }
                    }
                }
            }

            item {
                Text(
                    "Privacy: call data stays on your device. Voice messages are never stored permanently — "
                        + "they are delivered and removed. Nothing is uploaded without your consent.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------- small building blocks

@Composable
private fun RecordingRow(
    recording: com.quickvoice.core.data.repository.CallRecording,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onPlay,
            shape = CircleShape,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Stop" else "Play",
                tint = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(7.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(recording.fileName, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${formatRecordingSize(recording.sizeBytes)} · ${formatRecordingTime(recording.timestamp)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete recording",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatRecordingSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}

private fun formatRecordingTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(7.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun PermissionRowView(row: PermissionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.granted) Icons.Filled.Check else Icons.Filled.Security,
            contentDescription = null,
            tint = if (row.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                row.explanation,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (row.granted) "Granted" else "Ask",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (row.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Binds the six VoIP text fields and returns them as one sextuple when the user saves. */
@Composable
private fun VoipTextField(
    label: String,
    value: String,
    userId: String,
    displayName: String,
    placeholder: String,
    turnUrl: String,
    turnUsername: String,
    turnPassword: String,
    onSave: (Sextuple<String, String, String, String, String, String>) -> Unit,
) {
    Column {
        var serverUrl by rememberSaveable { mutableStateOf("") }
        var userIdField by rememberSaveable { mutableStateOf("") }
        var displayNameField by rememberSaveable { mutableStateOf("") }
        var turnServerUrl by rememberSaveable { mutableStateOf("") }
        var turnUser by rememberSaveable { mutableStateOf("") }
        var turnPass by rememberSaveable { mutableStateOf("") }

        LaunchedEffect(value, userId, displayName, turnUrl, turnUsername, turnPassword) {
            if (serverUrl.isBlank()) serverUrl = value
            if (userIdField.isBlank()) userIdField = userId
            if (displayNameField.isBlank()) displayNameField = displayName
            if (turnServerUrl.isBlank()) turnServerUrl = turnUrl
            if (turnUser.isBlank()) turnUser = turnUsername
            if (turnPass.isBlank()) turnPass = turnPassword
        }

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = userIdField,
            onValueChange = { userIdField = it },
            label = { Text("User id (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = displayNameField,
            onValueChange = { displayNameField = it },
            label = { Text("Display name (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Text(
            "TURN (relay) server — required for calls between different networks.",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 14.dp),
        )
        OutlinedTextField(
            value = turnServerUrl,
            onValueChange = { turnServerUrl = it },
            label = { Text("TURN URL") },
            placeholder = { Text("turn:your-server:3478") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = turnUser,
            onValueChange = { turnUser = it },
            label = { Text("TURN username") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = turnPass,
            onValueChange = { turnPass = it },
            label = { Text("TURN password") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Button(
            onClick = { onSave(Sextuple(serverUrl, userIdField, displayNameField, turnServerUrl, turnUser, turnPass)) },
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text("Save")
        }
    }
}

/** Minimal container so the VoIP card can hand back six values at once. */
private data class Sextuple<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
)

private fun SignalingState.label(): String = when (this) {
    SignalingState.DISCONNECTED -> "Disconnected"
    SignalingState.CONNECTING -> "Connecting…"
    SignalingState.CONNECTED -> "Connected"
    SignalingState.REGISTERED -> "Registered (ready)"
}

/** Starts the system "install unknown apps" screen for this app. */
private fun startUnknownSources(context: Context, intent: Intent?) {
    if (intent != null) {
        context.startActivity(intent)
    }
}

/** Stored value meaning "play no ringtone for incoming calls". */
private const val SILENT_RINGTONE = "silent"

private fun UpdateState.statusText(): String = when (this) {
    UpdateState.Idle -> "No check performed yet."
    UpdateState.Checking -> "Checking for updates…"
    is UpdateState.UpToDate -> "You are up to date (v$versionName)."
    is UpdateState.Available ->
        if (downloaded) "Version v${info.versionName} downloaded — ready to install."
        else "New version v${info.versionName} is available."
    is UpdateState.Downloading -> "Downloading ${(progress * 100).toInt()}%"
    is UpdateState.Failed -> message
}
