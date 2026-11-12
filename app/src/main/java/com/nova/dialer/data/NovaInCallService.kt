package com.nova.dialer.data

import android.content.Context
import android.content.Intent
import android.os.Build
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
            // v1.7: a RINGING call wins over an active one — before this,
            // a second incoming call during a call could never be answered,
            // because the active call was always primary and the ringing
            // call never got Answer/Decline buttons.
            it.state == Call.STATE_RINGING
        } ?: _calls.value.firstOrNull {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING ||
                it.state == Call.STATE_CONNECTING
        } ?: _calls.value.firstOrNull()

    /** The other call, if any (for the call-waiting switch button). */
    val secondaryCall: Call?
        get() {
            val primary = primaryCall ?: return null
            return _calls.value.firstOrNull { it != primary }
        }
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

        private const val CALL_NOTIFICATION_ID = 1001
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
                updateCallNotification()
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                CallStateHolder.refresh()
                updateCallNotification()
            }
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        CallStateHolder.add(call)
        updateCallNotification()
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
        updateCallNotification()
    }

    /**
     * v1.7: an ongoing-call notification, so the user can always get back
     * to the in-call screen. Without it, pressing Back during a call left
     * the call running with no UI and no way to mute or end it.
     */
    private fun updateCallNotification() {
        val calls = CallStateHolder.calls.value
        if (calls.isEmpty()) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(CALL_NOTIFICATION_ID)
            return
        }
        val primary = CallStateHolder.primaryCall
        val number = primary?.details?.handle?.schemeSpecificPart ?: "Unknown"
        val name = primary?.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: number
        val channelId = "nova_ongoing_call"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Ongoing call", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows the current call so you can return to it" }
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // Platform Notification.Builder (no androidx dependency needed).
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("On call — $name")
            .setContentText("Tap to return to the call")
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_CALL)
            .build()
        try {
            nm.notify(CALL_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // No notification permission (API 33+) — the in-call screen
            // itself still works; the BackHandler keeps it from being lost.
            ErrorLog.log(applicationContext, "InCall", "Couldn't post call notification", e)
        }
    }
}
