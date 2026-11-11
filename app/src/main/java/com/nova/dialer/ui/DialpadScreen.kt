package com.nova.dialer.ui

import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
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
import com.nova.dialer.data.ContactItem
import com.nova.dialer.data.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KEY_LETTERS = mapOf(
    '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL",
    '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ"
)

private fun toneFor(c: Char): Int = when (c) {
    '0' -> ToneGenerator.TONE_DTMF_0
    '1' -> ToneGenerator.TONE_DTMF_1
    '2' -> ToneGenerator.TONE_DTMF_2
    '3' -> ToneGenerator.TONE_DTMF_3
    '4' -> ToneGenerator.TONE_DTMF_4
    '5' -> ToneGenerator.TONE_DTMF_5
    '6' -> ToneGenerator.TONE_DTMF_6
    '7' -> ToneGenerator.TONE_DTMF_7
    '8' -> ToneGenerator.TONE_DTMF_8
    '9' -> ToneGenerator.TONE_DTMF_9
    '*' -> ToneGenerator.TONE_DTMF_S
    '#' -> ToneGenerator.TONE_DTMF_P
    else -> ToneGenerator.TONE_DTMF_0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadScreen(
    number: String,
    onNumberChange: (String) -> Unit,
    lastDialed: String?,
    onCall: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { CallRepo.loadContacts(context) }
    }

    // DTMF tones while dialling (a real dialer feel). Guarded: silence is
    // fine if the tone generator cannot start on this device.
    val toneGen = remember {
        try { ToneGenerator(AudioManager.STREAM_DTMF, 80) } catch (_: Exception) { null }
    }
    DisposableEffect(Unit) {
        onDispose { try { toneGen?.release() } catch (_: Exception) { } }
    }
    fun playTone(c: Char) {
        try { toneGen?.startTone(toneFor(c), 120) } catch (_: Exception) { }
    }

    fun press(c: Char) {
        if (number.length >= 20) return
        onNumberChange(number + c)
        playTone(c)
    }

    // T9 matches for the digits typed so far (name or number).
    val digits = PhoneUtils.digitsOnly(number)
    val t9Results = remember(digits, contacts) {
        if (digits.length < 2) emptyList()
        else contacts.filter { PhoneUtils.t9Matches(it.name, it.number, digits) }.take(5)
    }
    val exactMatch = remember(number, contacts) {
        val d = PhoneUtils.digitsOnly(number)
        if (d.isEmpty()) null
        else contacts.firstOrNull { PhoneUtils.digitsOnly(it.number) == d }
            ?: contacts.firstOrNull { PhoneUtils.digitsOnly(it.number).endsWith(d) && d.length >= 7 }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Number display + matched contact name.
        Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    number.ifEmpty { " " },
                    fontSize = 32.sp, fontWeight = FontWeight.Light,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                exactMatch?.let {
                    Text(it.name, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = { if (number.isNotEmpty()) onNumberChange(number.dropLast(1)) },
                enabled = number.isNotEmpty()
            ) { Icon(Icons.Filled.Backspace, "Delete") }
        }
        // T9 results.
        if (t9Results.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(t9Results) { c ->
                    ListItem(
                        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(PhoneUtils.prettyNumber(c.number)) },
                        leadingContent = { ContactAvatar(c) },
                        modifier = Modifier.clickable { onNumberChange(c.number); onCall(c.number) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Keypad.
        val keys = listOf(
            listOf('1', '2', '3'), listOf('4', '5', '6'),
            listOf('7', '8', '9'), listOf('*', '0', '#')
        )
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { c ->
                    KeyButton(
                        digit = c,
                        letters = KEY_LETTERS[c] ?: if (c == '0') "+" else "",
                        onClick = { press(c) },
                        onLongClick = {
                            when (c) {
                                '0' -> { onNumberChange(number + "+"); playTone(c) }
                                '1' -> {
                                    val vm = CallRepo.voicemailNumber(context)
                                    if (vm.isNullOrBlank()) onStatus("No voicemail number on this phone")
                                    else onCall(vm)
                                }
                                else -> press(c)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Call button.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilledIconButton(
                onClick = {
                    when {
                        number.isNotBlank() -> onCall(number)
                        lastDialed != null -> { onNumberChange(lastDialed); onStatus("Last number: $lastDialed") }
                        else -> onStatus("Type a number first")
                    }
                },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = NovaGood)
            ) { Icon(Icons.Filled.Call, "Call", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Long-press 0 for +  •  Long-press 1 for voicemail",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(digit: Char, letters: String, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Box(
        Modifier.size(76.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit.toString(), fontSize = 28.sp, fontWeight = FontWeight.Normal)
            if (letters.isNotEmpty()) {
                Text(letters, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ContactAvatar(c: ContactItem) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            c.name.firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold, fontSize = 20.sp
        )
    }
}
