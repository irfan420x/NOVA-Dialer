package com.nova.dialer.data

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.nova.dialer.ui.CallActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared call state. The InCallService updates it as calls come and go;
 * CallActivity observes it to draw the in-call screen.
 */
object CallStateHolder {
    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()

    fun add(call: Call) {
        _calls.value = _calls.value + call
    }

    fun remove(call: Call) {
        _calls.value = _calls.value - call
    }

    /** Re-emit so the UI refreshes when a Call object mutates in place. */
    fun refresh() {
        _calls.value = _calls.value.toList()
    }

    /** The call the user is talking to right now (or the first one). */
    val primaryCall: Call?
        get() = _calls.value.firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING ||
                it.state == Call.STATE_RINGING || it.state == Call.STATE_CONNECTING
        } ?: _calls.value.firstOrNull()
}

/**
 * The system binds this service for every call once NOVA Dialer is set as
 * the default phone app. It never dials or records anything itself — it
 * only shows the in-call screen and drives the call the user controls.
 * `instance` lets the in-call screen drive mute / audio routing, which
 * live on the service (not on the Call object).
 */
class NovaInCallService : InCallService() {

    private val callbacks = mutableMapOf<Call, Call.Callback>()

    companion object {
        var instance: NovaInCallService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                CallStateHolder.refresh()
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                CallStateHolder.refresh()
            }
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        CallStateHolder.add(call)
        // Bring the in-call screen to the front.
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            ErrorLog.log(applicationContext, "InCall", "Failed to open call screen", e)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        CallStateHolder.remove(call)
    }
}
