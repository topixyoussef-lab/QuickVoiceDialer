package com.quickvoice.core.model

/**
 * A number the system asked the default dialer to handle, produced from an
 * incoming ACTION_DIAL / ACTION_CALL intent.
 */
data class DialRequest(val action: Action, val number: String) {
    enum class Action { DIAL, CALL }
}
