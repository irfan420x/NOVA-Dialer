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
import com.nova.dialer.data.CallRepo
import com.nova.dialer.data.CallStateHolder
import com.nova.dialer.data.ErrorLog
import com.nova.dialer.data.NovaInCallService
import com.nova.dialer.data.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Call timer: starts the moment a call becomes active and keeps
    // counting through hold — it only stops when the call disconnects.
    // v1.8: the timer is keyed to the CALL, not just the state — with call
    // waiting the old code kept the first call's start time for the second
    // call, so the second call showed the first call's elapsed time.
    var activeSince by remember { mutableStateOf<Long?>(null) }
    var timedCall by remember { mutableStateOf<Call?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state, call) {
        if (state == Call.STATE_ACTIVE && timedCall !== call) {
            timedCall = call
            activeSince = System.currentTimeMillis()
            elapsed = 0
        }
        if (state == Call.STATE_DISCONNECTED) {
            activeSince = null
            timedCall = null
        }
    }
    LaunchedEffect(activeSince) {
        while (activeSince != null) {
            elapsed = (System.currentTimeMillis() - (activeSince ?: 0L)) / 1000
            delay(1000)
        }
    }

    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTick by remember { mutableStateOf(0) }
    // v1.8: status clears 3s after the LAST message — the old effect only
    // re-ran when the text changed, so repeating the same message never
    // reset its timer.
    LaunchedEffect(statusTick) {
        if (status != null) {
            delay(3000)
            status = null
        }
    }
    fun setStatus(msg: String) { status = msg; statusTick++ }
    // v1.8: hold is derived from the call itself, not remembered — a
    // remembered flag went stale across call-waiting switches and add-call.
    val held = state == Call.STATE_HOLDING
    // v1.8: the SIM list for the choose-SIM state (see below).
    var pickerSims by remember { mutableStateOf<List<CallRepo.SimInfo>?>(null) }
    LaunchedEffect(state) {
        if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            pickerSims = withContext(Dispatchers.IO) { CallRepo.listSims(context) }
        }
    }
    // v1.8: DTMF tones are serialized through one job — rapid taps used
    // to launch a coroutine each, and an earlier tap's stopDtmfTone() cut
    // off the later tap's tone mid-play.
    var dtmfJob by remember { mutableStateOf<Job?>(null) }
    // v1.4: remember the audio route before enabling the speaker, so
    // turning it off restores Bluetooth/earpiece instead of forcing earpiece.
    var routeBeforeSpeaker by remember { mutableStateOf(CallAudioState.ROUTE_EARPIECE) }
    val callScope = rememberCoroutineScope()

    fun safe(action: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            ErrorLog.log(context, "InCall", "Failed to $action", e)
            setStatus("Couldn't $action")
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
            when {
                // v1.8: the timer keeps showing through hold (was hidden the
                // moment the call was held, even though it kept counting).
                (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) && elapsed > 0 ->
                    if (state == Call.STATE_HOLDING) "${PhoneUtils.formatDuration(elapsed)} • On hold"
                    else PhoneUtils.formatDuration(elapsed)
                else -> callStateText(state)
            },
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
                // v1.8: one serialized job — the previous tone is cancelled
                // before the new one starts, so rapid taps can't clip each
                // other (a cancelled job's stop is skipped, because the new
                // tone has already replaced the old one).
                dtmfJob?.cancel()
                dtmfJob = callScope.launch {
                    try {
                        call.playDtmfTone(c)
                        delay(150)
                        call.stopDtmfTone()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
        } else if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            // v1.8: "Choose SIM" used to be a dead end — text with no
            // picker at all (e.g. when READ_PHONE_STATE was denied and no
            // SIM could be picked up front). Now the SIMs are listed and
            // the choice goes back to Telecom with phoneAccountSelected.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Choose a SIM for this call",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                when (val sims = pickerSims) {
                    null -> CircularProgressIndicator()
                    emptyList<CallRepo.SimInfo>() -> {
                        Text(
                            "No SIM could be read — check the phone permission, then try again.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    else -> sims.forEach { sim ->
                        OutlinedButton(
                            onClick = {
                                safe("select a SIM") { call.phoneAccountSelected(sim.handle, false) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(sim.displayName + if (sim.carrierName.isNotBlank()) " • ${sim.carrierName}" else "")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                FilledIconButton(
                    onClick = { safe("end the call") { call.disconnect() } },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = NovaMissed)
                ) { Icon(Icons.Filled.CallEnd, "Cancel call", Modifier.size(32.dp)) }
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
                    // v1.8: the buttons used to flip their UI state BEFORE
                    // the service/call was invoked, and a null service was
                    // a silent no-op — the UI showed "muted" while nothing
                    // was muted. Now the state flips only after the call
                    // succeeds, and a missing service says so.
                    CallActionButton(Icons.Filled.MicOff, "Mute", muted) {
                        val service = NovaInCallService.instance
                        if (service == null) setStatus("Couldn't mute — call service unavailable")
                        else safe("mute") { service.setMuted(!muted); muted = !muted }
                    }
                    CallActionButton(Icons.Filled.VolumeUp, "Speaker", speaker) {
                        val service = NovaInCallService.instance
                        if (service == null) setStatus("Couldn't switch speaker — call service unavailable")
                        else safe("switch speaker") {
                            if (!speaker) {
                                routeBeforeSpeaker = service.callAudioState?.route
                                    ?: CallAudioState.ROUTE_EARPIECE
                                service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                            } else {
                                service.setAudioRoute(routeBeforeSpeaker)
                            }
                            speaker = !speaker
                        }
                    }
                    CallActionButton(Icons.Filled.Pause, "Hold", held) {
                        safe(if (held) "resume the call" else "hold the call") {
                            if (held) call.unhold() else call.hold()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CallActionButton(Icons.Filled.Dialpad, "Keypad", showKeypad) { showKeypad = !showKeypad }
                    CallActionButton(Icons.Filled.PersonAdd, "Add call", false) {
                        safe("hold the call") { call.hold() }
                        val intent = android.content.Intent(context, MainActivity::class.java).apply {
                            // v1.8: SINGLE_TOP stacked a second MainActivity
                            // on top of the call screen every time (the
                            // activity was not on top, so single-top never
                            // deduped). REORDER_TO_FRONT reuses the existing
                            // MainActivity and keeps the call screen below it.
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
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
