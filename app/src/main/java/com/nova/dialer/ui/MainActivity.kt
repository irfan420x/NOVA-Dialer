package com.nova.dialer.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nova.dialer.data.CallRepo
import com.nova.dialer.data.ErrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DialerTab { KEYPAD, RECENTS, CONTACTS }

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Record every crash in the in-app error log (same pattern as NOVA Files).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                ErrorLog.log(applicationContext, "CRASH", "Uncaught exception on ${thread.name}", e)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, e)
        }
        // A tel: link (or ACTION_DIAL) opens the keypad with the number filled in.
        val dialNumber = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: ""
        setContent { NovaApp(dialNumber) }
    }
}

private fun loadDarkTheme(context: Context): Boolean =
    context.getSharedPreferences("nova_dialer", Context.MODE_PRIVATE).getBoolean("ui_dark_theme", true)

private fun saveDarkTheme(context: Context, dark: Boolean) {
    context.getSharedPreferences("nova_dialer", Context.MODE_PRIVATE)
        .edit().putBoolean("ui_dark_theme", dark).apply()
}

private fun isDefaultDialer(context: Context): Boolean {
    return try {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.defaultDialerPackage == context.packageName
    } catch (_: Exception) { false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaApp(initialDialNumber: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(DialerTab.KEYPAD) }
    var dialNumber by remember { mutableStateOf(initialDialNumber) }
    var darkTheme by remember { mutableStateOf(loadDarkTheme(context)) }
    var showErrorLog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var recentsRefreshKey by remember { mutableStateOf(0) }
    var lastDialed by remember { mutableStateOf<String?>(null) }
    var permissionsGranted by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var skipPermissions by remember { mutableStateOf(false) }
    var isDefault by remember { mutableStateOf(isDefaultDialer(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = REQUIRED_PERMISSIONS.all { results[it] == true }
        scope.launch {
            lastDialed = withContext(Dispatchers.IO) {
                CallRepo.loadRecents(context, 50)
                    .firstOrNull { it.type == android.provider.CallLog.Calls.OUTGOING_TYPE }?.number
            }
        }
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { isDefault = isDefaultDialer(context) }

    LaunchedEffect(Unit) {
        if (permissionsGranted) {
            lastDialed = withContext(Dispatchers.IO) {
                CallRepo.loadRecents(context, 50)
                    .firstOrNull { it.type == android.provider.CallLog.Calls.OUTGOING_TYPE }?.number
            }
        }
    }
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(3000)
            status = null
        }
    }

    fun onStatus(msg: String) { status = msg }

    // v1.5: dual-SIM choice. The saved default SIM (a phone-account id) is
    // remembered in SharedPreferences; "Ask every time" clears it.
    val prefs = remember { context.getSharedPreferences("nova_dialer", Context.MODE_PRIVATE) }
    var defaultSimId by remember { mutableStateOf(prefs.getString("default_sim_id", null)) }
    var simPickerNumber by remember { mutableStateOf<String?>(null) }
    var simPickerSims by remember { mutableStateOf<List<CallRepo.SimInfo>>(emptyList()) }

    fun doPlaceCall(number: String, handle: android.telecom.PhoneAccountHandle?) {
        val clean = number.trim()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { CallRepo.placeCall(context, clean, handle) }
            if (!ok) onStatus("Couldn't place the call")
            else {
                lastDialed = clean
                recentsRefreshKey++
            }
        }
    }

    fun placeCall(number: String) {
        val clean = number.trim()
        if (clean.isEmpty()) { onStatus("Type a number first"); return }
        if (!CallRepo.hasCallPermission(context)) {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
            onStatus("Call permission is needed to place calls")
            return
        }
        scope.launch {
            val sims = withContext(Dispatchers.IO) { CallRepo.listSims(context) }
            val saved = defaultSimId?.let { id -> sims.firstOrNull { it.handle.id == id } }
            if (saved != null) {
                doPlaceCall(clean, saved.handle)
            } else if (sims.size >= 2) {
                simPickerSims = sims
                simPickerNumber = clean
            } else {
                doPlaceCall(clean, null)
            }
        }
    }

    fun pickSim(sim: CallRepo.SimInfo, always: Boolean) {
        if (always) {
            prefs.edit().putString("default_sim_id", sim.handle.id).apply()
            defaultSimId = sim.handle.id
        }
        val number = simPickerNumber
        simPickerNumber = null
        simPickerSims = emptyList()
        if (number != null) doPlaceCall(number, sim.handle)
    }

    fun requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                } else {
                    isDefault = isDefaultDialer(context)
                }
            } else {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Dialer", "Failed to request default-dialer role", e)
            onStatus("Couldn't open the default-dialer setting")
        }
    }

    NovaTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (showErrorLog) {
                BackHandler { showErrorLog = false }
                ErrorLogScreen(onBack = { showErrorLog = false })
            } else if (tab != DialerTab.KEYPAD) {
                // Back from Recents/Contacts goes to the keypad, not out of the app.
                BackHandler { tab = DialerTab.KEYPAD }
                return@Surface
            }
            if (!permissionsGranted && !skipPermissions) {
                PermissionScreen(
                    onGrant = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                    onSkip = { skipPermissions = true }
                )
                return@Surface
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Dialer", fontWeight = FontWeight.Bold) },
                        actions = {
                            if (!isDefault) {
                                TextButton(onClick = { requestDefaultDialer() }) { Text("Set as default") }
                            }
                            IconButton(onClick = { showErrorLog = true }) { Icon(Icons.Filled.BugReport, "Error log") }
                            IconButton(onClick = {
                                darkTheme = !darkTheme
                                saveDarkTheme(context, darkTheme)
                            }) {
                                Icon(
                                    if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    if (darkTheme) "Light theme" else "Dark theme"
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = tab == DialerTab.KEYPAD,
                            onClick = { tab = DialerTab.KEYPAD },
                            icon = { Icon(Icons.Filled.Dialpad, null) },
                            label = { Text("Keypad") }
                        )
                        NavigationBarItem(
                            selected = tab == DialerTab.RECENTS,
                            onClick = { tab = DialerTab.RECENTS; recentsRefreshKey++ },
                            icon = { Icon(Icons.Filled.History, null) },
                            label = { Text("Recents") }
                        )
                        NavigationBarItem(
                            selected = tab == DialerTab.CONTACTS,
                            onClick = { tab = DialerTab.CONTACTS },
                            icon = { Icon(Icons.Filled.Contacts, null) },
                            label = { Text("Contacts") }
                        )
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    Column(Modifier.fillMaxSize()) {
                        status?.let {
                            Text(
                                it, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        when (tab) {
                            DialerTab.KEYPAD -> DialpadScreen(
                                number = dialNumber,
                                onNumberChange = { dialNumber = it },
                                lastDialed = lastDialed,
                                simLabel = defaultSimId?.let { id ->
                                    simPickerSims.firstOrNull { it.handle.id == id }?.displayName
                                        ?: "SIM"
                                },
                                onSimClick = {
                                    scope.launch {
                                        val sims = withContext(Dispatchers.IO) { CallRepo.listSims(context) }
                                        if (sims.size >= 2) {
                                            simPickerSims = sims
                                            simPickerNumber = null
                                        } else {
                                            onStatus("Only one SIM on this phone")
                                        }
                                    }
                                },
                                onCall = { placeCall(it) },
                                onStatus = ::onStatus
                            )
                            DialerTab.RECENTS -> RecentsScreen(
                                refreshKey = recentsRefreshKey,
                                onCall = { placeCall(it) },
                                onStatus = ::onStatus
                            )
                            DialerTab.CONTACTS -> ContactsScreen(
                                onCall = { placeCall(it) },
                                onStatus = ::onStatus
                            )
                        }
                    }
                }
            }

            // v1.5: dual-SIM picker — shown when placing a call with two SIMs
            // and no saved default, or when tapping the SIM label on the keypad.
            if (simPickerSims.size >= 2) {
                val calling = simPickerNumber
                AlertDialog(
                    onDismissRequest = { simPickerNumber = null; simPickerSims = emptyList() },
                    title = { Text(if (calling != null) "Call with which SIM?" else "Default SIM") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            simPickerSims.forEach { sim ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(sim.displayName, fontWeight = FontWeight.Bold)
                                        if (sim.carrierName.isNotBlank()) {
                                            Text(sim.carrierName, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Row {
                                        TextButton(onClick = { pickSim(sim, false) }) { Text("Once") }
                                        TextButton(onClick = { pickSim(sim, true) }) { Text("Always") }
                                    }
                                }
                            }
                            if (calling == null && defaultSimId != null) {
                                TextButton(onClick = {
                                    prefs.edit().remove("default_sim_id").apply()
                                    defaultSimId = null
                                    simPickerSims = emptyList()
                                    onStatus("SIM choice cleared — will ask every time")
                                }) { Text("Ask every time") }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { simPickerNumber = null; simPickerSims = emptyList() }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Call, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("NOVA Dialer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "To show your calls and contacts and place calls, NOVA Dialer needs access to contacts, the call log and the phone. Your data stays on your phone — nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, Modifier.fillMaxWidth()) { Text("Allow access") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, Modifier.fillMaxWidth()) { Text("Continue without it") }
    }
}
