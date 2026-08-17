package com.quickvoice.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.quickvoice.core.design.theme.QuickVoiceTheme
import com.quickvoice.core.model.DialRequest
import com.quickvoice.feature.call.CallRoute
import com.quickvoice.feature.call.CallViewModel
import com.quickvoice.feature.home.HomeRoute
import com.quickvoice.feature.home.HomeViewModel
import com.quickvoice.feature.settings.SettingsRoute
import com.quickvoice.feature.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Host activity. While a call is live it renders the full-screen call route on top of
 * everything; otherwise it shows the home navigation. Also receives the
 * "com.quickvoice.dialer.action.SHOW_CALL" intent from the VoIP incoming-call trampoline
 * and the ACTION_DIAL / ACTION_CALL intents the system sends to the default dialer.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    /**
     * One-shot dial request produced by an incoming DIAL/CALL intent. Consumed by the
     * home screen once it is composed (see [consumeDialRequest]).
     */
    private val pendingDialRequest = MutableStateFlow<DialRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val pending by pendingDialRequest.collectAsState()
            QuickVoiceTheme {
                AppRoot(
                    pendingDialRequest = pending,
                    onDialRequestConsumed = { pendingDialRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Parses DIAL / CALL / SHOW_CALL intents into a number the home screen can use. */
    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val number = (intent?.data as? Uri)
            ?.takeIf { it.scheme == "tel" }
            ?.schemeSpecificPart
            ?.orEmpty()
            ?.take(24)
            ?: return
        if (number.isBlank()) return
        when (action) {
            Intent.ACTION_CALL -> pendingDialRequest.value = DialRequest(action = DialRequest.Action.CALL, number = number)
            Intent.ACTION_DIAL -> pendingDialRequest.value = DialRequest(action = DialRequest.Action.DIAL, number = number)
        }
    }
}

@Composable
private fun AppRoot(
    appViewModel: AppViewModel = hiltViewModel(),
    pendingDialRequest: DialRequest?,
    onDialRequestConsumed: () -> Unit,
) {
    val session by appViewModel.activeSession.collectAsStateWithLifecycle()

    if (session != null) {
        val callViewModel: CallViewModel = hiltViewModel()
        CallRoute(viewModel = callViewModel)
    } else {
        val navController = rememberNavController()
        QuickVoiceNavHost(
            navController = navController,
            pendingDialRequest = pendingDialRequest,
            onDialRequestConsumed = onDialRequestConsumed,
        )
    }
}

@Composable
private fun QuickVoiceNavHost(
    navController: NavHostController,
    pendingDialRequest: DialRequest?,
    onDialRequestConsumed: () -> Unit,
) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val viewModel: HomeViewModel = hiltViewModel()
            HomeRoute(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate("settings") },
                pendingDialRequest = pendingDialRequest,
                onDialRequestConsumed = onDialRequestConsumed,
            )
        }
        composable("settings") {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
