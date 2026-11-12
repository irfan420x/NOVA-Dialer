package com.nova.dialer.ui

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.dialer.data.CallStateHolder
import com.nova.dialer.data.ErrorLog
import com.nova.dialer.data.NovaInCallService
import com.nova.dialer.data.PhoneUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-call screen. The system shows this through NovaInCallService when
 * NOVA Dialer is the default phone app. It works over the lock screen.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            NovaTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CallScreenContent(onFinish = { finish() })
                }
            }
        }
    }
}

private fun callStateText(state: Int): String = when (state) {
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_DIALING -> "Dialing…"
    Call.STATE_CONNECTING -> "Connecting…"
    Call.STATE_ACTIVE -> "On call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTED -> "Call ended"
    Call.STATE_DISCONNECTING -> "Ending…"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
    else -> ""
}

@Composable
private fun CallScreenContent(onFinish: () -> Unit) {
    val context = LocalContext.current
    val calls by CallStateHolder.calls.collectAsState()
    val call = CallStateHolder.primaryCall

    // No call left — close the screen.
    LaunchedEffect(calls) {
        if (calls.isEmpty()) onFinish()
    }
    if (call == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val details = call.details
    val number = details.handle?.schemeSpecificPart ?: "Unknown"
    val name = details.callerDisplayName?.takeIf { it.isNotBlank() } ?: number
    val state = call.state

    // v1.7: Back during a call must NOT close the in-call screen — before
    // this, pressing Back left the call running with no UI, no notification
    // and no way to mute or end it. The ongoing-call notification (posted
    // by NovaInCallService) is the way back if the user leaves via Home.
    androidx.activity.compose.BackHandler { }

    // Call timer: starts the moment the call becomes active and keeps
    // counting through hold — it only stops when the call disconnects.
    var activeSince by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state) {
        if (state == Call.STATE_ACTIVE && activeSince == null) activeSince = System.currentTimeMillis()
        if (state == Call.STATE_DISCONNECTED) activeSince = null
    }
    LaunchedEffect(activeSince) {
        while (activeSince != null) {
            elapsed = (System.currentTimeMillis() - (activeSince ?: 0L)) / 1000
            delay(1000)
        }
    }

    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // v1.4: remember the audio route before enabling the speaker, so
    // turning it off restores Bluetooth/earpiece instead of forcing earpiece.
    var routeBeforeSpeaker by remember { mutableStateOf(CallAudioState.ROUTE_EARPIECE) }
    val callScope = rememberCoroutineScope()

    fun safe(action: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            ErrorLog.log(context, "InCall", "Failed to $action", e)
            status = "Couldn't $action"
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                fontSize = 40.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(PhoneUtils.prettyNumber(number), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            if (state == Call.STATE_ACTIVE && elapsed > 0) PhoneUtils.formatDuration(elapsed) else callStateText(state),
            color = MaterialTheme.colorScheme.primary
        )
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))

        if (showKeypad) {
            DtmfPad(onTone = { c ->
                // v1.4: play the tone for ~150ms — playing and stopping in
                // the same frame cut the tone off before it could be heard.
                callScope.launch {
                    try {
                        call.playDtmfTone(c)
                        delay(150)
                        call.stopDtmfTone()
                    } catch (e: Exception) {
                        ErrorLog.log(context, "InCall", "Failed to send tone", e)
                    }
                }
            })
            Spacer(Modifier.height(16.dp))
        }

        // Incoming call: accept / decline.
        if (state == Call.STATE_RINGING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledIconButton(
                    onClick = { safe("decline the call") { call.reject(VideoProfile.STATE_AUDIO_ONLY) } },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NovaMissed)
                ) { Icon(Icons.Filled.CallEnd, "Decline", Modifier.size(32.dp)) }
                FilledIconButton(
                    onClick = { safe("answer the call") { call.answer(VideoProfile.STATE_AUDIO_ONLY) } },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NovaGood)
                ) { Icon(Icons.Filled.Call, "Answer", Modifier.size(32.dp)) }
            }
        } else {
            // Active / dialing: mute, speaker, hold, keypad, add call, end.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // v1.7: call waiting — when a second call exists, show a
                // switch button that holds this call and resumes the other.
                val other = CallStateHolder.secondaryCall
                if (other != null) {
                    val otherNumber = other.details.handle?.schemeSpecificPart ?: "Unknown"
                    val otherName = other.details.callerDisplayName?.takeIf { it.isNotBlank() } ?: otherNumber
                    OutlinedButton(onClick = {
                        safe("switch calls") {
                            call.hold()
                            other.unhold()
                        }
                    }) {
                        Icon(Icons.Filled.SwapCalls, "Switch call")
                        Spacer(Modifier.width(8.dp))
                        Text("Switch to $otherName")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CallActionButton(Icons.Filled.MicOff, "Mute", muted) {
                        muted = !muted
                        safe("mute") { NovaInCallService.instance?.setMuted(muted) }
                    }
                    CallActionButton(Icons.Filled.VolumeUp, "Speaker", speaker) {
                        speaker = !speaker
                        safe("switch speaker") {
                            val service = NovaInCallService.instance
                            if (speaker) {
                                routeBeforeSpeaker = service?.callAudioState?.route
                                    ?: CallAudioState.ROUTE_EARPIECE
                                service?.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                            } else {
                                service?.setAudioRoute(routeBeforeSpeaker)
                            }
                        }
                    }
                    CallActionButton(Icons.Filled.Pause, "Hold", held) {
                        held = !held
                        safe(if (held) "hold the call" else "resume the call") {
                            if (held) call.hold() else call.unhold()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CallActionButton(Icons.Filled.Dialpad, "Keypad", showKeypad) { showKeypad = !showKeypad }
                    CallActionButton(Icons.Filled.PersonAdd, "Add call", false) {
                        // v1.4: the Hold button must show the held state too.
                        held = true
                        safe("hold the call") { call.hold() }
                        val intent = android.content.Intent(context, MainActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        try { context.startActivity(intent) } catch (e: Exception) {
                            ErrorLog.log(context, "InCall", "Failed to open dialer for add-call", e)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                FilledIconButton(
                    onClick = { safe("end the call") { call.disconnect() } },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NovaMissed)
                ) { Icon(Icons.Filled.CallEnd, "End call", Modifier.size(32.dp)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CallActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
        ) { Icon(icon, label, Modifier.size(24.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DtmfPad(onTone: (Char) -> Unit) {
    val keys = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    FilledIconButton(
                        onClick = { onTone(c) },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) { Text(c.toString(), fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
