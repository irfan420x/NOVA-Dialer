package com.nova.dialer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.dialer.data.CallRepo
import com.nova.dialer.data.ErrorLog
import com.nova.dialer.data.PhoneUtils
import com.nova.dialer.data.RecentCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecentsFilter { ALL, MISSED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    refreshKey: Int,
    onCall: (String) -> Unit,
    onStatus: (String) -> Unit,
    // v1.8: the way back to permissions after "Continue without it".
    onRequestPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recents by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var filter by remember { mutableStateOf(RecentsFilter.ALL) }
    var selected by remember { mutableStateOf<RecentCall?>(null) }
    var details by remember { mutableStateOf<RecentCall?>(null) }
    var loading by remember { mutableStateOf(true) }

    // v1.8: loads are serialized — overlapping loads used to leave stale
    // data or a stuck spinner.
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            try {
                val data = withContext(Dispatchers.IO) { CallRepo.loadRecents(context) }
                if (isActive) recents = data
            } finally {
                if (isActive) loading = false
            }
        }
    }
    LaunchedEffect(refreshKey) { load() }
    // v1.8: reload on resume too — a call made, a contact added or a
    // permission granted in Settings used to be invisible until restart.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shown = remember(recents, filter) {
        if (filter == RecentsFilter.MISSED) recents.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else recents
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == RecentsFilter.ALL,
                    onClick = { filter = RecentsFilter.ALL },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = filter == RecentsFilter.MISSED,
                    onClick = { filter = RecentsFilter.MISSED },
                    label = { Text("Missed") }
                )
            }
            if (filter == RecentsFilter.MISSED && shown.isNotEmpty()) {
                TextButton(onClick = {
                    scope.launch {
                        if (CallRepo.clearMissedCalls(context)) { onStatus("Missed calls cleared"); load() }
                        else onStatus("Couldn't clear missed calls")
                    }
                }) { Text("Clear missed") }
            }
        }
        when {
            !CallRepo.hasCallLogPermission(context) -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text("Call history needs permission", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Allow access to the call log to see your recent calls here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRequestPermissions) { Text("Allow access") }
                    }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == RecentsFilter.MISSED) "No missed calls" else "No recent calls",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { call ->
                    RecentRow(
                        call = call,
                        onClick = { onCall(call.number) },
                        onLongClick = { selected = call }
                    )
                }
            }
        }
    }

    // Long-press menu.
    selected?.let { call ->
        // v1.8: isBlocked/canBlockNumbers are provider/IPC calls — they
        // were queried on the main thread during composition, janking the
        // sheet open. Load them off-thread; the label updates when ready.
        var blocked by remember(call) { mutableStateOf(false) }
        var canBlock by remember(call) { mutableStateOf(false) }
        LaunchedEffect(call) {
            val pair = withContext(Dispatchers.IO) {
                CallRepo.isBlocked(context, call.number) to CallRepo.canBlockNumbers(context)
            }
            blocked = pair.first
            canBlock = pair.second
        }
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
                Text(call.name ?: PhoneUtils.prettyNumber(call.number), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(PhoneUtils.prettyNumber(call.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                MenuRow(Icons.Filled.Call, "Call back") { selected = null; onCall(call.number) }
                MenuRow(Icons.Filled.ContentCopy, "Copy number") {
                    selected = null
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Phone number", call.number))
                    onStatus("Number copied")
                }
                MenuRow(Icons.Filled.PersonAdd, "Add to contacts") {
                    selected = null
                    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, call.number)
                    }
                    try { context.startActivity(intent) } catch (e: Exception) {
                        ErrorLog.log(context, "Recents", "Failed to open contact editor", e)
                        onStatus("Couldn't open contacts")
                    }
                }
                if (canBlock) {
                    // v1.8: the icon was Icons.Filled.Block in BOTH states,
                    // and a failed (un)block blamed the default-dialer role
                    // even when the row was only shown because blocking is
                    // allowed. Both are fixed here.
                    MenuRow(
                        if (blocked) Icons.Filled.RemoveCircleOutline else Icons.Filled.Block,
                        if (blocked) "Unblock number" else "Block number"
                    ) {
                        selected = null
                        scope.launch {
                            val ok = CallRepo.setBlocked(context, call.number, !blocked)
                            onStatus(
                                if (ok) (if (blocked) "Number unblocked" else "Number blocked")
                                else (if (blocked) "Couldn't unblock this number" else "Couldn't block this number")
                            )
                            load()
                        }
                    }
                }
                MenuRow(Icons.Filled.Delete, "Delete from log") {
                    selected = null
                    scope.launch {
                        if (CallRepo.deleteCallLogEntries(context, listOf(call.id))) load()
                        else onStatus("Couldn't delete this entry")
                    }
                }
                MenuRow(Icons.Filled.Info, "Details") { selected = null; details = call }
            }
        }
    }

    details?.let { call ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(call.name ?: PhoneUtils.prettyNumber(call.number), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(PhoneUtils.prettyNumber(call.number))
                    Spacer(Modifier.height(8.dp))
                    Text(callTypeLabel(call.type))
                    // v1.8: missed calls have no duration — showing
                    // "Duration 0:00" for them was meaningless.
                    if (call.durationSeconds > 0) Text("Duration ${PhoneUtils.formatDuration(call.durationSeconds)}")
                    Text(PhoneUtils.relativeTime(call.date))
                    call.simLabel?.let { Text("SIM: $it") }
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }
        )
    }
}

private fun callTypeLabel(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "Incoming call"
    CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
    CallLog.Calls.MISSED_TYPE -> "Missed call"
    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
    CallLog.Calls.REJECTED_TYPE -> "Rejected call"
    CallLog.Calls.BLOCKED_TYPE -> "Blocked call"
    else -> "Call"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(call: RecentCall, onClick: () -> Unit, onLongClick: () -> Unit) {
    val missed = call.type == CallLog.Calls.MISSED_TYPE
    val typeIcon = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> Icons.Filled.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade
        CallLog.Calls.MISSED_TYPE -> Icons.Filled.CallMissed
        CallLog.Calls.VOICEMAIL_TYPE -> Icons.Filled.Voicemail
        CallLog.Calls.REJECTED_TYPE -> Icons.Filled.CallMissedOutgoing
        CallLog.Calls.BLOCKED_TYPE -> Icons.Filled.Block
        else -> Icons.Filled.Call
    }
    ListItem(
        headlineContent = {
            Text(
                call.name ?: PhoneUtils.prettyNumber(call.number),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (missed) NovaMissed else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (missed) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                "${callTypeLabel(call.type)} • ${PhoneUtils.relativeTime(call.date)}" +
                    if (call.durationSeconds > 0) " • ${PhoneUtils.formatDuration(call.durationSeconds)}" else "" +
                    (call.simLabel?.let { " • $it" } ?: ""),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            if (call.photoUri != null) {
                coil.compose.AsyncImage(
                    model = call.photoUri, contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                )
            } else {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (call.name ?: call.number).firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onClick) { Icon(Icons.Filled.Call, "Call back") }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

@Composable
fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
