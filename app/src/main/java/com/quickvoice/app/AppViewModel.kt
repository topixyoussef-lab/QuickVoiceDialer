package com.quickvoice.app

import androidx.lifecycle.ViewModel
import com.quickvoice.core.call.CallController
import com.quickvoice.core.model.CallSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Activity-scoped view model. Exposes the single active call so the app root can
 * swap between the home navigation and the full-screen call UI.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val callController: CallController,
) : ViewModel() {

    val activeSession: StateFlow<CallSession?> = callController.activeSession
}
