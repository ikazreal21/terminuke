package com.terminuke.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.terminuke.app.data.db.HostEntity
import com.terminuke.app.data.db.SshKeyEntity
import com.terminuke.app.domain.HostInput
import com.terminuke.app.domain.HostValidator
import com.terminuke.app.service.SshService
import com.terminuke.app.ssh.HostKeyInfo
import com.terminuke.app.ssh.SessionState
import com.terminuke.app.terminal.RemoteTerminalBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TerminukeApp).container
        setContent { TerminukeTheme { TerminukeScreen(container) } }
    }
}

private enum class AppPage { HOSTS, KEYS, SETTINGS }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TerminukeScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(AppPage.HOSTS) }
    var search by remember { mutableStateOf("") }
    val hosts by remember(search) { container.hosts.observe(search) }.collectAsState(initial = emptyList())
    val keys by container.keys.observe().collectAsState(initial = emptyList())
    val session by container.sessions.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = com.terminuke.app.data.AppSettings())
    val stopSession: () -> Unit = {
        container.sessions.disconnect()
        context.stopService(Intent(context, SshService::class.java))
    }
    var editingHost by remember { mutableStateOf<HostEntity?>(null) }
    var showNewHost by remember { mutableStateOf(false) }
    var connectingHost by remember { mutableStateOf<HostEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<HostEntity?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(settings.secureFlag) {
        val window = context.findComponentActivity()?.window
        if (settings.secureFlag) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(container.hosts.exportJson()) }
                    ?: error("Could not open the selected file.")
            }.onFailure { errorMessage = it.message }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected file.")
                container.hosts.importJson(json)
            }.onFailure { errorMessage = it.message }
        }
    }
    val keyImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected key file.")
            }.getOrElse { errorMessage = it.message; return@launch }
            try {
                runCatching { container.keys.importPrivatePem("Imported key", bytes) }
                    .onFailure { errorMessage = it.message }
            } finally {
                bytes.fill(0)
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val connected = session as? SessionState.Connected
    if (connected != null) {
        SessionScreen(
            host = connected.host,
            shell = connected.shell,
            fontSize = settings.fontSizeSp,
            keepScreenOn = settings.keepScreenOn,
            onDisconnect = stopSession,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("terminuke", color = Color(0xFF80CBC4)) },
                actions = {
                    TextButton(onClick = { page = AppPage.HOSTS }) { Text("Hosts") }
                    TextButton(onClick = { page = AppPage.KEYS }) { Text("Keys") }
                    TextButton(onClick = { page = AppPage.SETTINGS }) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        when (page) {
            AppPage.HOSTS -> HostList(
                hosts = hosts,
                search = search,
                onSearch = { search = it },
                onAdd = { showNewHost = true },
                onConnect = { connectingHost = it },
                onEdit = { editingHost = it },
                onDelete = { pendingDelete = it },
                modifier = Modifier.padding(padding),
            )
            AppPage.KEYS -> KeyList(
                keys = keys,
                onGenerate = {
                    scope.launch { runCatching { container.keys.generateEd25519("Ed25519 key") }.onFailure { errorMessage = it.message } }
                },
                onGenerateRsa = {
                    scope.launch { runCatching { container.keys.generateRsa("RSA-3072 key") }.onFailure { errorMessage = it.message } }
                },
                onImport = { keyImportLauncher.launch(arrayOf("*/*")) },
                onDelete = { key -> scope.launch { runCatching { container.keys.delete(key.alias) }.onFailure { errorMessage = it.message } } },
                modifier = Modifier.padding(padding),
            )
            AppPage.SETTINGS -> SettingsScreen(
                settings = settings,
                onFontSize = { scope.launch { container.settings.setFontSize(it) } },
                onKeepScreenOn = { scope.launch { container.settings.setKeepScreenOn(it) } },
                onSecureFlag = { scope.launch { container.settings.setSecureFlag(it) } },
                onExport = { exportLauncher.launch("terminuke-hosts.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showNewHost || editingHost != null) {
        HostEditorDialog(
            host = editingHost,
            keys = keys,
            onDismiss = { showNewHost = false; editingHost = null },
            onSave = { input ->
                scope.launch {
                    runCatching { container.hosts.save(input) }
                        .onSuccess { showNewHost = false; editingHost = null }
                        .onFailure { errorMessage = it.message }
                }
            },
        )
    }

    connectingHost?.let { host ->
        ConnectDialog(
            host = host,
            keys = keys,
            onDismiss = { connectingHost = null },
            onConnect = { password, keyPassphrase, selectedKey ->
                connectingHost = null
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ContextCompat.startForegroundService(context, Intent(context, SshService::class.java))
                scope.launch {
                    runCatching {
                        val pem = selectedKey?.let { container.keys.loadPrivatePem(it.alias) }
                        container.sessions.connect(host, password?.toCharArray(), pem, keyPassphrase?.toCharArray())
                    }.onFailure { errorMessage = it.message }
                }
            },
        )
    }

    (session as? SessionState.AwaitingHostTrust)?.let { pending ->
        HostTrustDialog(pending.key, onTrustOnce = { container.sessions.answerHostTrust(true) },
            onTrustAlways = { container.sessions.answerHostTrust(true, always = true) },
            onReject = { container.sessions.answerHostTrust(false) })
    }
    (session as? SessionState.Connecting)?.let { connecting ->
        AlertDialog(
            onDismissRequest = stopSession,
            title = { Text("Connecting") },
            text = { Text("Opening a secure SSH connection to ${connecting.hostLabel}…") },
            confirmButton = { TextButton(onClick = stopSession) { Text("Cancel") } },
        )
    }
    (session as? SessionState.AwaitingAuthResponse)?.let { pending ->
        AuthResponseDialog(
            prompt = pending.prompt,
            echo = pending.echo,
            onSubmit = { container.sessions.answerAuthPrompt(it.toCharArray()) },
            onCancel = stopSession,
        )
    }
    (session as? SessionState.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = stopSession,
            title = { Text("Connection failed") },
            text = { Text(failed.message) },
            confirmButton = { TextButton(onClick = stopSession) { Text("Close") } },
        )
    }
    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${host.label}?") },
            text = { Text("This removes the saved host only. It does not affect anything on the server.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.hosts.delete(host.id); pendingDelete = null } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    errorMessage?.let { message ->
        AlertDialog(onDismissRequest = { errorMessage = null; stopSession() }, title = { Text("Something went wrong") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null; stopSession() }) { Text("OK") } })
    }
}

@Composable
private fun HostList(
    hosts: List<HostEntity>, search: String, onSearch: (String) -> Unit,
    onAdd: () -> Unit, onConnect: (HostEntity) -> Unit, onEdit: (HostEntity) -> Unit,
    onDelete: (HostEntity) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(search, onSearch, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Search hosts") }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Saved hosts", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onAdd) { Text("Add host") }
        }
        if (hosts.isEmpty()) {
            Text("No hosts yet. Add one with your server name, address, and username.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(hosts, key = { it.id }) { host ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onEdit(host) }) {
                                Text(host.label, style = MaterialTheme.typography.titleMedium)
                                Text("${host.username}@${host.hostname}:${host.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onConnect(host) }) { Text("Connect") }
                            TextButton(onClick = { onDelete(host) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostEditorDialog(host: HostEntity?, keys: List<SshKeyEntity>, onDismiss: () -> Unit, onSave: (HostInput) -> Unit) {
    var label by remember(host) { mutableStateOf(host?.label.orEmpty()) }
    var hostname by remember(host) { mutableStateOf(host?.hostname.orEmpty()) }
    var port by remember(host) { mutableStateOf(host?.port?.toString() ?: "22") }
    var username by remember(host) { mutableStateOf(host?.username.orEmpty()) }
    var authType by remember(host) { mutableStateOf(host?.authType ?: "password") }
    var keyAlias by remember(host) { mutableStateOf(host?.keyAlias) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (host == null) "Add SSH host" else "Edit SSH host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField("Label", label, { label = it }, errors["label"])
                EditorField("Hostname or IP", hostname, { hostname = it }, errors["hostname"])
                EditorField("Port", port, { port = it }, errors["port"], KeyboardType.Number)
                EditorField("Username", username, { username = it }, errors["username"])
                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChoice("Password", selected = authType == "password") { authType = "password"; keyAlias = null }
                    FilterChoice("SSH key", selected = authType == "key") { authType = "key"; keyAlias = keys.firstOrNull()?.alias }
                    FilterChoice("Interactive", selected = authType == "keyboard-interactive") { authType = "keyboard-interactive"; keyAlias = null }
                }
                if (authType == "key" && keyAlias != null) {
                    keys.forEach { key ->
                        TextButton(onClick = { keyAlias = key.alias }) { Text(if (key.alias == keyAlias) "✓ ${key.name}" else key.name) }
                    }
                }
                if (authType == "key" && keyAlias == null) Text("Generate or import a key, then select it here.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val input = HostInput(
                    id = host?.id ?: 0, label = label, hostname = hostname, port = port,
                    username = username, authType = authType, keyAlias = keyAlias.takeIf { authType == "key" },
                )
                errors = HostValidator.validate(input)
                if (errors.isEmpty()) onSave(input)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectDialog(host: HostEntity, keys: List<SshKeyEntity>, onDismiss: () -> Unit, onConnect: (String?, String?, SshKeyEntity?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var keyPassphrase by remember { mutableStateOf("") }
    val selectedKey = keys.firstOrNull { it.alias == host.keyAlias }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${host.label}") },
        text = {
            if (host.authType == "keyboard-interactive") {
                Text("The server will request each password or one-time code during authentication. Responses are used only for this connection.")
            } else if (host.authType == "key" && selectedKey != null) {
                Column {
                    Text("Connect using ${selectedKey.name}. Private key material is decrypted only for this connection.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(keyPassphrase, { keyPassphrase = it }, label = { Text("Key passphrase (if set)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            } else if (host.authType == "key") {
                Text("This host has no selected private key. Edit the host and choose a saved key.")
            } else {
                Column {
                    Text("Password is used for this connection only and is not saved.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("SSH password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(enabled = host.authType != "key" || selectedKey != null, onClick = {
            onConnect(password.takeIf { host.authType == "password" }, keyPassphrase.takeIf { selectedKey != null }, selectedKey)
            password = ""
            keyPassphrase = ""
        }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AuthResponseDialog(prompt: String, echo: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var response by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("SSH authentication") },
        text = {
            Column {
                Text(prompt.ifBlank { "Enter the requested response." })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    label = { Text("Response") },
                    visualTransformation = if (echo) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(response); response = "" }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun HostTrustDialog(key: HostKeyInfo, onTrustOnce: () -> Unit, onTrustAlways: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown SSH server") },
        text = { Text("Verify this fingerprint using a trusted route before accepting.\n\n${key.hostname}:${key.port}\n${key.keyType}\n${key.fingerprint}") },
        confirmButton = { TextButton(onClick = onTrustAlways) { Text("Trust always") } },
        dismissButton = {
            Row {
                TextButton(onClick = onTrustOnce) { Text("Once") }
                TextButton(onClick = onReject) { Text("Reject") }
            }
        },
    )
}

@Composable
private fun SessionScreen(host: HostEntity, shell: com.terminuke.app.ssh.RemoteShell, fontSize: Int, keepScreenOn: Boolean, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val bridge = remember(shell) { RemoteTerminalBridge(context, shell, onClosed = onDisconnect) }
    DisposableEffect(keepScreenOn) {
        val window = context.findComponentActivity()?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepScreenOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${host.username}@${host.hostname}", modifier = Modifier.weight(1f), color = Color(0xFF80CBC4))
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        AndroidView(
            factory = { bridge.terminalView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            update = { it.setTextSize(fontSize) },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL-C" to "\u0003", "CTRL-D" to "\u0004", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "|" to "|", "~" to "~")
                .forEach { (label, value) -> OutlinedButton(onClick = { bridge.send(value.toByteArray()) }) { Text(label) } }
        }
    }
}

@Composable
private fun KeyList(keys: List<SshKeyEntity>, onGenerate: () -> Unit, onGenerateRsa: () -> Unit, onImport: () -> Unit, onDelete: (SshKeyEntity) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Local SSH keys", style = MaterialTheme.typography.titleLarge)
        Text("Private keys are encrypted using the Android Keystore and never included in host exports.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            Button(onClick = onGenerate) { Text("Generate Ed25519") }
            OutlinedButton(onClick = onGenerateRsa) { Text("Generate RSA-3072") }
            OutlinedButton(onClick = onImport) { Text("Import PEM") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keys, key = { it.alias }) { key ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.name)
                            Text(key.publicOpenSsh.ifBlank { "Imported key · public key preview unavailable" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        TextButton(onClick = { onDelete(key) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: com.terminuke.app.data.AppSettings,
    onFontSize: (Int) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onSecureFlag: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Terminal settings", style = MaterialTheme.typography.titleLarge)
        Text("Font size: ${settings.fontSizeSp} sp")
        Slider(value = settings.fontSizeSp.toFloat(), onValueChange = { onFontSize(it.toInt()) }, valueRange = 12f..24f, steps = 11)
        SettingSwitch("Keep screen on during a session", settings.keepScreenOn, onKeepScreenOn)
        SettingSwitch("Hide terminal in screenshots and Recents", settings.secureFlag, onSecureFlag)
        HorizontalDivider()
        Text("Local backup", style = MaterialTheme.typography.titleMedium)
        Text("Export contains saved hosts only. Android app backup is disabled; exported files never contain credentials or private keys.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport) { Text("Export hosts") }
            OutlinedButton(onClick = onImport) { Text("Import hosts") }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun EditorField(label: String, value: String, onValue: (String) -> Unit, error: String?, keyboardType: KeyboardType = KeyboardType.Text) {
    Column {
        OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), isError = error != null)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    if (selected) Button(onClick = onSelect) { Text(label) } else OutlinedButton(onClick = onSelect) { Text(label) }
}

@Composable
private fun TerminukeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF80CBC4),
            onPrimary = Color(0xFF00201D),
            background = Color(0xFF101411),
            surface = Color(0xFF171D19),
            surfaceVariant = Color(0xFF242D27),
            secondary = Color(0xFFB8CCBC),
        ),
        content = content,
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
