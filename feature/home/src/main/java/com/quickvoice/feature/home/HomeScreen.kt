package com.quickvoice.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickvoice.core.design.components.InitialsAvatar
import com.quickvoice.core.model.CallDirection
import com.quickvoice.core.model.CallState
import com.quickvoice.core.model.CallType
import com.quickvoice.core.model.Contact
import com.quickvoice.core.model.RecentCall
import com.quickvoice.core.quickvoice.QuickVoiceStatus
import com.quickvoice.core.quickvoice.QuickVoiceUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The runtime permissions this app really needs, with the rationale shown in Settings. */
object CallPermissions {
    val ALL = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    fun missing(context: Context): List<String> = ALL.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()
}

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    pendingDialRequest: com.quickvoice.core.model.DialRequest? = null,
    onDialRequestConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val quickVoiceState by viewModel.quickVoiceController.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionMissing by remember { mutableStateOf(!CallPermissions.hasAll(context)) }

    // Handle numbers sent to us by the system as the default dialer (DIAL pre-fills
    // the pad, CALL places the call straight away through the SIM).
    LaunchedEffect(pendingDialRequest) {
        val request = pendingDialRequest ?: return@LaunchedEffect
        when (request.action) {
            com.quickvoice.core.model.DialRequest.Action.DIAL -> viewModel.onPasteNumber(request.number)
            com.quickvoice.core.model.DialRequest.Action.CALL -> viewModel.placeCall(request.number, CallType.SIM)
        }
        onDialRequestConsumed()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionMissing = !CallPermissions.hasAll(context)
        viewModel.refreshDefaultDialer()
        viewModel.searchContacts(uiState.searchQuery)
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshDefaultDialer()
    }

    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.consumeNotice()
    }

    HomeScreen(
        uiState = uiState,
        quickVoiceState = quickVoiceState,
        hasAllPermissions = !permissionMissing,
        snackbarHostState = snackbarHostState,
        onRequestPermissions = { permissionLauncher.launch(CallPermissions.missing(context).toTypedArray()) },
        onRequestDialerRole = {
            viewModel.requestDialerRoleIntent()?.let { roleLauncher.launch(it) }
        },
        onOpenSettings = onOpenSettings,
        onDialDigit = viewModel::onDialDigit,
        onBackspace = viewModel::onBackspace,
        onClearDial = viewModel::onClearDial,
        onPasteNumber = viewModel::onPasteNumber,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSelectCallType = viewModel::setDefaultCallType,
        onPlaceNumber = { viewModel.placeCall(uiState.dialNumber) },
        onPlaceContact = viewModel::placeContact,
        onPlaceContactSim = viewModel::callContactViaSim,
        onPlaceContactVoip = viewModel::callContactViaVoip,
        onIntercomContact = viewModel::intercomContact,
        onIntercomNumber = { viewModel.intercomNumber(uiState.dialNumber) },
        onPlaceRecent = viewModel::placeRecent,
        onSaveNumber = viewModel::saveNumber,
        onDeleteSaved = viewModel::deleteSavedNumber,
        onToggleQuickVoice = viewModel::setQuickVoiceEnabled,
        onStartMessageTo = viewModel::startMessageTo,
        onSendVoiceMessageTo = viewModel::sendVoiceMessageTo,
        onStopMessageTo = viewModel::stopMessageTo,
        onCancelMessageMode = viewModel::cancelMessageMode,
        onInstallUpdate = viewModel::installPendingUpdate,
        onDismissUpdate = viewModel::dismissUpdateBanner,
        onDismissDialerRole = viewModel::dismissDialerBanner,
        onDismissCrash = viewModel::dismissCrashReport,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: HomeUiState,
    quickVoiceState: QuickVoiceUiState,
    hasAllPermissions: Boolean,
    snackbarHostState: SnackbarHostState,
    onRequestPermissions: () -> Unit,
    onRequestDialerRole: () -> Unit,
    onOpenSettings: () -> Unit,
    onDialDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClearDial: () -> Unit,
    onPasteNumber: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectCallType: (CallType) -> Unit,
    onPlaceNumber: () -> Unit,
    onPlaceContact: (Contact) -> Unit,
    onPlaceContactSim: (Contact) -> Unit,
    onPlaceContactVoip: (Contact) -> Unit,
    onIntercomContact: (Contact) -> Unit,
    onIntercomNumber: () -> Unit,
    onPlaceRecent: (RecentCall) -> Unit,
    onSaveNumber: (String, String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onToggleQuickVoice: (Boolean) -> Unit,
    onStartMessageTo: (String, String) -> Unit,
    onSendVoiceMessageTo: (String, String) -> Unit,
    onStopMessageTo: () -> Unit,
    onCancelMessageMode: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onDismissDialerRole: () -> Unit,
    onDismissCrash: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    val tabs = listOf(
        TabData("Contacts", Icons.Filled.Contacts),
        TabData("Dial", Icons.Filled.Dialpad),
        TabData("Recent", Icons.Filled.History),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Voice", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            QuickVoiceToggleCard(
                enabled = uiState.quickVoiceEnabled,
                onToggle = onToggleQuickVoice,
            )

            if (!hasAllPermissions) {
                BannerCard(
                    title = "Permissions needed",
                    body = "Microphone, contacts and phone access let you place calls and use Quick Voice.",
                    buttonText = "Grant",
                    tone = BannerTone.WARNING,
                    onClick = onRequestPermissions,
                )
            }

            if (!uiState.isDefaultDialer && !uiState.dialerBannerDismissed) {
                BannerCard(
                    title = "Make QuickVoice your phone app",
                    body = "Needed to keep the in-app call screen and auto-speaker during SIM calls.",
                    buttonText = "Set as default",
                    tone = BannerTone.INFO,
                    onClick = onRequestDialerRole,
                    onDismiss = onDismissDialerRole,
                )
            }

            if (uiState.updateReady) {
                BannerCard(
                    title = "Update v${uiState.updateVersionName} ready",
                    body = "A new version was downloaded automatically. Install it to keep Quick Voice updated.",
                    buttonText = "Install",
                    tone = BannerTone.INFO,
                    onClick = onInstallUpdate,
                    onDismiss = onDismissUpdate,
                )
            }

            uiState.crashReport?.let { crash ->
                CrashCard(crash, onDismiss = onDismissCrash)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                        0 -> ContactsTab(
                            query = uiState.searchQuery,
                            contacts = uiState.contacts,
                            savedContacts = uiState.savedContacts,
                            hasPermission = hasAllPermissions,
                            onQueryChange = onSearchQueryChange,
                            onRequestPermission = onRequestPermissions,
                            onCall = onPlaceContact,
                            onCallSim = onPlaceContactSim,
                            onCallVoip = onPlaceContactVoip,
                            onIntercom = onIntercomContact,
                            onDeleteSaved = onDeleteSaved,
                            onStartMessageTo = onStartMessageTo,
                        )

                        1 -> DialPadTab(
                            number = uiState.dialNumber,
                            defaultType = uiState.defaultCallType,
                            signalingRegistered = uiState.signalingState.name == "REGISTERED",
                            myNumber = uiState.voipUserId,
                            onDigit = onDialDigit,
                            onBackspace = onBackspace,
                            onClear = onClearDial,
                            onPaste = onPasteNumber,
                            onSelectType = onSelectCallType,
                            onCall = onPlaceNumber,
                            onIntercomNumber = onIntercomNumber,
                            onStartMessageTo = onSendVoiceMessageTo,
                            onSaveNumber = onSaveNumber,
                        )

                    else -> RecentsTab(
                        recents = uiState.recents,
                        onCall = onPlaceRecent,
                        onStartMessageTo = onSendVoiceMessageTo,
                    )
                }
            }

            // Leave-a-message recording overlay (works without an active call).
            if (quickVoiceState.messageTarget != null || quickVoiceState.isRecording) {
                VoiceMessageOverlay(
                    quickVoiceState = quickVoiceState,
                    onStart = { quickVoiceState.messageTarget?.let { onStartMessageTo(it.peerId, it.displayName) } },
                    onStop = onStopMessageTo,
                    onCancel = onCancelMessageMode,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ shared bits

enum class BannerTone { INFO, WARNING }

@Composable
private fun CrashCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("The app crashed", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    message,
                    fontSize = 12.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun BannerCard(
    title: String,
    body: String,
    buttonText: String,
    tone: BannerTone,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val container = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(body, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onClick) {
                Text(buttonText, fontSize = 13.sp)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun QuickVoiceToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Quick Voice Mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Hold the mic during a call to send a short voice message",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

// ---------------------------------------------------------------- contacts tab

@Composable
private fun ContactsTab(
    query: String,
    contacts: List<Contact>,
    savedContacts: List<Contact>,
    hasPermission: Boolean,
    onQueryChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onCall: (Contact) -> Unit,
    onCallSim: (Contact) -> Unit,
    onCallVoip: (Contact) -> Unit,
    onIntercom: (Contact) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onStartMessageTo: (String, String) -> Unit,
) {
    val matchingSaved = if (query.isBlank()) {
        savedContacts
    } else {
        savedContacts.filter {
            it.name.contains(query, ignoreCase = true) || it.phoneNumber.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search contacts") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        when {
            !hasPermission -> EmptyState(
                title = "Contacts permission needed",
                body = "Grant contacts access to call people from your address book.",
                buttonText = "Grant permission",
                onClick = onRequestPermission,
            )

            matchingSaved.isEmpty() && contacts.isEmpty() -> EmptyState(
                title = if (query.isBlank()) "No contacts" else "No results",
                body = if (query.isBlank()) "Your address book is empty." else "Nothing matches \"$query\".",
                buttonText = null,
                onClick = {},
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                if (matchingSaved.isNotEmpty()) {
                    item(key = "saved_header") { SectionHeader("Saved") }
                    items(matchingSaved, key = { it.lookupKey }) { contact ->
                        SavedContactRow(
                            contact = contact,
                            onClick = { onCall(contact) },
                            onVoipClick = { onCallVoip(contact) },
                            onIntercomClick = { if (contact.voipId != null) onIntercom(contact) },
                            onDelete = { onDeleteSaved(contact.phoneNumber) },
                            onMessage = { onStartMessageTo(contact.voipId ?: contact.phoneNumber, contact.name) },
                        )
                    }
                }
                if (contacts.isNotEmpty()) {
                    item(key = "all_header") {
                        SectionHeader(if (query.isBlank()) "All contacts" else "Results")
                    }
                    items(contacts, key = { it.lookupKey + it.phoneNumber }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = { onCall(contact) },
                            onSimClick = { onCallSim(contact) },
                            onVoipClick = { if (contact.voipId != null) onCallVoip(contact) },
                            onIntercomClick = { if (contact.voipId != null) onIntercom(contact) },
                            onMessage = { onStartMessageTo(contact.voipId ?: contact.phoneNumber, contact.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SavedContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onVoipClick: () -> Unit,
    onIntercomClick: () -> Unit,
    onDelete: () -> Unit,
    onMessage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(name = contact.name)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                contact.phoneNumber,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(6.dp)
                .size(20.dp),
        )
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Leave a voice message",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .padding(6.dp)
                .size(24.dp)
                .clickable(onClick = onMessage),
        )
        if (contact.voipId != null) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = "Intercom",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .clickable(onClick = onIntercomClick),
            )
            Icon(
                Icons.Filled.Wifi,
                contentDescription = "Wi-Fi call",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .clickable(onClick = onVoipClick),
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Remove",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(6.dp)
                .size(20.dp)
                .clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onSimClick: () -> Unit,
    onVoipClick: () -> Unit,
    onIntercomClick: () -> Unit,
    onMessage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(name = contact.name)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                contact.phoneNumber,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Leave a voice message",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .padding(6.dp)
                .size(24.dp)
                .clickable(onClick = onMessage),
        )
        if (contact.voipId != null) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = "Intercom",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .clickable(onClick = onIntercomClick),
            )
            Icon(
                Icons.Filled.Wifi,
                contentDescription = "Wi-Fi call",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(6.dp)
                    .size(20.dp)
                    .clickable(onClick = onVoipClick),
            )
        }
        Icon(
            Icons.Filled.Call,
            contentDescription = "SIM call",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(6.dp)
                .size(22.dp)
                .clickable(onClick = onSimClick),
        )
    }
}

// ------------------------------------------------------------------ dial pad

@Composable
private fun DialPadTab(
    number: String,
    defaultType: CallType,
    signalingRegistered: Boolean,
    myNumber: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onPaste: (String) -> Unit,
    onSelectType: (CallType) -> Unit,
    onCall: () -> Unit,
    onIntercomNumber: () -> Unit,
    onStartMessageTo: (String, String) -> Unit,
    onSaveNumber: (String, String) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                if (number.isEmpty()) {
                    Text(
                        "Enter a number",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = number,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = {
                    if (number.isNotBlank()) showSaveDialog = true
                },
                enabled = number.isNotBlank(),
            ) {
                Icon(
                    Icons.Filled.BookmarkBorder,
                    contentDescription = "Save number",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (defaultType == CallType.VOIP) {
            Text(
                text = if (myNumber.isBlank()) {
                    "Your number: not set — add it in Settings"
                } else {
                    "Your number: $myNumber"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallTypeChip("SIM", Icons.Filled.SignalCellularAlt, CallType.SIM, defaultType, onSelectType)
            CallTypeChip("Wi-Fi", Icons.Filled.Wifi, CallType.VOIP, defaultType, onSelectType)
        }

        Spacer(modifier = Modifier.height(10.dp))

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#"),
        )
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { key -> DialKey(key, onDigit) }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Filled.Backspace, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                Surface(
                    onClick = onCall,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Phone, contentDescription = "Call", modifier = Modifier.size(30.dp))
                    }
                }
            }
            IconButton(
                onClick = onIntercomNumber,
                modifier = Modifier.size(52.dp),
                enabled = number.isNotBlank() && defaultType == CallType.VOIP && signalingRegistered,
            ) {
                Icon(
                    Icons.Filled.Campaign,
                    contentDescription = "Intercom",
                    tint = if (defaultType == CallType.VOIP) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            IconButton(
                onClick = { onStartMessageTo(number, number) },
                modifier = Modifier.size(52.dp),
                enabled = number.isNotBlank(),
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Leave a voice message",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when (defaultType) {
                CallType.SIM -> "Calls via SIM"
                CallType.VOIP -> if (signalingRegistered) "Calls via Wi-Fi" else "Wi-Fi calls need the VoIP server (Settings)"
            },
            fontSize = 12.sp,
            color = if (defaultType == CallType.VOIP && !signalingRegistered) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showSaveDialog) {
        SaveNumberDialog(
            number = number,
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                onSaveNumber(number, name)
            },
        )
    }
}

@Composable
private fun CallTypeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    type: CallType,
    selected: CallType,
    onSelect: (CallType) -> Unit,
) {
    FilterChip(
        selected = selected == type,
        onClick = { onSelect(type) },
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
    )
}

@Composable
private fun DialKey(key: String, onDigit: (String) -> Unit) {
    Surface(
        onClick = { onDigit(key) },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(60.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = key, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ------------------------------------------------------------------ recents tab

@Composable
private fun RecentsTab(
    recents: List<RecentCall>,
    onCall: (RecentCall) -> Unit,
    onStartMessageTo: (String, String) -> Unit,
) {
    if (recents.isEmpty()) {
        EmptyState(
            title = "No recent calls",
            body = "Calls you place or receive will show up here.",
            buttonText = null,
            onClick = {},
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(recents, key = { it.id }) { call ->
            RecentRow(
                call = call,
                onClick = { onCall(call) },
                onMessage = {
                    val name = call.displayName ?: call.number
                    onStartMessageTo(call.number, name)
                },
            )
        }
    }
}

@Composable
private fun RecentRow(
    call: RecentCall,
    onClick: () -> Unit,
    onMessage: () -> Unit,
) {
    val name = call.displayName ?: call.number
    val directionIcon = when (call.direction) {
        CallDirection.OUTGOING -> Icons.Filled.CallMade
        CallDirection.INCOMING -> Icons.Filled.CallReceived
    }
    val stateColor = when (call.state) {
        CallState.MISSED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsAvatar(name = name)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(directionIcon, contentDescription = null, tint = stateColor, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${call.type.name} · ${formatDuration(call.durationMs)}",
                    fontSize = 12.sp,
                    color = stateColor,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTimestamp(call.timestamp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Filled.Mic,
            contentDescription = "Leave a voice message",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(22.dp)
                .clickable(onClick = onMessage),
        )
        Icon(
            Icons.Filled.Call,
            contentDescription = "Call again",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(22.dp)
                .clickable(onClick = onClick),
        )
    }
}

// -------------------------------------------------------------- save dialog

@Composable
private fun SaveNumberDialog(
    number: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save number") },
        text = {
            Column {
                Text(
                    number,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ------------------------------------------------------- leave-a-message overlay

@Composable
private fun VoiceMessageOverlay(
    quickVoiceState: QuickVoiceUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val target = quickVoiceState.messageTarget
    val name = target?.displayName?.ifBlank { target.peerId } ?: ""

    // Auto-clear Sent/Error banners a couple of seconds after they appear.
    val status = quickVoiceState.status
    LaunchedEffect(status) {
        if (status is QuickVoiceStatus.Sent || status is QuickVoiceStatus.Error) {
            kotlinx.coroutines.delay(2_500)
            onCancel()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = status) {
                is QuickVoiceStatus.Recording -> {
                    Text("Recording…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (name.isNotBlank()) {
                        Text(
                            "Voice message for $name",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${s.elapsedMs / 1000}s",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop and send", modifier = Modifier.size(28.dp))
                        }
                    }
                }

                is QuickVoiceStatus.Sending -> {
                    Text("Sending…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                is QuickVoiceStatus.Sent -> {
                    Text("Voice message sent", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                }

                is QuickVoiceStatus.Error -> {
                    Text(s.message, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                else -> {
                    Text("Leave a voice message", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (name.isNotBlank()) {
                        Text(
                            "for $name",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        onClick = onStart,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Filled.Mic, contentDescription = "Record", modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// ------------------------------------------------------------------ empty state

@Composable
private fun EmptyState(
    title: String,
    body: String,
    buttonText: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            body,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttonText != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

private data class TabData(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// ------------------------------------------------------------------ formatting

private val timestampFormatter = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())

private fun formatTimestamp(epochMillis: Long): String = timestampFormatter.format(Date(epochMillis))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).toInt()
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (totalSeconds <= 0) "no answer" else "%02d:%02d".format(m, s)
}
