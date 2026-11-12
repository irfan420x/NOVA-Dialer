package com.nova.dialer.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import coil.compose.AsyncImage
import com.nova.dialer.data.CallRepo
import com.nova.dialer.data.ContactItem
import com.nova.dialer.data.ErrorLog
import com.nova.dialer.data.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onCall: (String) -> Unit,
    onStatus: (String) -> Unit,
    // v1.8: the way back to permissions after "Continue without it" —
    // NovaApp passes the permission launcher through here.
    onRequestPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<ContactItem?>(null) }

    // v1.8: loads are serialized — a resume reload racing a tab-switch
    // reload used to leave stale data or a stuck spinner.
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            try {
                val data = withContext(Dispatchers.IO) { CallRepo.loadContacts(context) }
                if (isActive) contacts = data
            } finally {
                if (isActive) loading = false
            }
        }
    }
    // v1.8: reload on every resume, not just once — a contact added or
    // edited in the system editor used to be invisible until restart.
    // (ON_RESUME also fires on first launch, so this is the only load.)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // In-memory filter (avoids re-querying the provider on every keystroke).
    // v1.8: the shared matcher — the old inline filter returned every
    // contact for any name query (contains("") is always true).
    val filtered = remember(query, contacts) {
        val q = query.trim()
        if (q.isEmpty()) contacts
        else contacts.filter { PhoneUtils.contactMatchesQuery(it.name, it.number, q) }
    }
    val favorites = remember(contacts) { contacts.filter { it.starred }.distinctBy { it.lookupKey } }
    val searching = query.isNotBlank()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                    try { context.startActivity(intent) } catch (e: Exception) {
                        ErrorLog.log(context, "Contacts", "Failed to open new-contact editor", e)
                        onStatus("Couldn't open contacts")
                    }
                },
                // v1.9: Nebula gradient FAB (16dp), white icon.
                modifier = Modifier.background(NovaGradientBrush, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.Transparent
            ) { Icon(Icons.Filled.PersonAdd, "Add contact", tint = Color.White) }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search name or number") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                !CallRepo.hasContactsPermission(context) -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            // v1.9: Nebula empty state — icon in a gradient circle.
                            Box(
                                Modifier.size(64.dp).clip(CircleShape).background(NovaGradientBrush),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Contacts, null, Modifier.size(28.dp), tint = Color.White) }
                            Spacer(Modifier.height(16.dp))
                            Text("Contacts need permission", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Allow access to contacts to see them here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onRequestPermissions) { Text("Allow access") }
                        }
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(NovaGradientBrush),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PersonSearch, null, Modifier.size(28.dp), tint = Color.White) }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (searching) "No contacts match \"$query\"" else "No contacts yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (!searching && favorites.isNotEmpty()) {
                        item {
                            Text(
                                "Favorites", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        item {
                            LazyRow(
                                Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(favorites, key = { it.id }) { c ->
                                    Column(
                                        Modifier.width(72.dp).clickable { onCall(c.number) },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        ContactPhoto(c, 56)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            c.name.split(" ").firstOrNull() ?: c.name,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        item {
                            Text(
                                "All contacts", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(PhoneUtils.prettyNumber(c.number)) },
                            leadingContent = { ContactPhoto(c, 48) },
                            trailingContent = {
                                Row {
                                    // v1.9: favorites use the Nebula pink accent.
                                    if (c.starred) Icon(
                                        Icons.Filled.Star, "Favorite",
                                        tint = NovaTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    IconButton(onClick = { onCall(c.number) }) {
                                        Icon(Icons.Filled.Call, "Call", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { onCall(c.number) },
                                onLongClick = { detail = c }
                            )
                        )
                    }
                }
            }
        }
    }

    detail?.let { c ->
        var starred by remember(c) { mutableStateOf(c.starred) }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(PhoneUtils.prettyNumber(c.number))
                    Spacer(Modifier.height(4.dp))
                    if (c.timesContacted > 0) Text("${c.timesContacted} calls", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                if (CallRepo.setStarred(context, c.contactId, !starred)) {
                                    starred = !starred
                                    load()
                                } else onStatus("Couldn't update favorite")
                            }
                        }) {
                            Icon(
                                if (starred) Icons.Filled.Star else Icons.Filled.StarBorder, null,
                                tint = NovaTertiary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (starred) "Favorited" else "Favorite")
                        }
                        OutlinedButton(onClick = {
                            detail = null
                            // v1.8: Uri.fromParts keeps '#' in the number —
                            // Uri.parse("smsto:…") truncated it.
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", c.number, null))
                            try { context.startActivity(intent) } catch (e: Exception) {
                                ErrorLog.log(context, "Contacts", "Failed to open messaging", e)
                                onStatus("Couldn't open messages")
                            }
                        }) {
                            Icon(Icons.Filled.Message, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Message")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        detail = null
                        val intent = Intent(Intent.ACTION_EDIT, android.content.ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, c.contactId
                        ))
                        try { context.startActivity(intent) } catch (e: Exception) {
                            ErrorLog.log(context, "Contacts", "Failed to open contact editor", e)
                            onStatus("Couldn't open contacts")
                        }
                    }) { Text("Edit contact") }
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null; onCall(c.number) }) { Text("Call") }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } }
        )
    }
}

@Composable
fun ContactPhoto(c: ContactItem, sizeDp: Int) {
    if (c.photoUri != null) {
        AsyncImage(
            model = c.photoUri, contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(sizeDp.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                c.name.firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
