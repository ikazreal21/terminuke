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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.terminuke.app.data.db.HostEntity
import com.terminuke.app.data.db.SshKeyEntity
import com.terminuke.app.domain.HostInput
import com.terminuke.app.domain.HostValidator
import com.terminuke.app.service.SshService
import com.terminuke.app.ssh.HostKeyInfo
import com.terminuke.app.ssh.SessionState
import com.terminuke.app.terminal.RemoteTerminalBridge
import com.terminuke.app.terminal.terminalFontSizePixels
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

private object ArchivePalette {
    val paper = Color(0xFFE9E3D8)
    val white = Color(0xFFF7F5F0)
    val graphite = Color(0xFF292826)
    val black = Color(0xFF101112)
    val ink = Color(0xFF262522)
    val muted = Color(0xFF827B70)
    val rule = Color(0xFFBDB5A9)
    val copper = Color(0xFF8C604A)
    val paleText = Color(0xFFD8D1C6)
}

@Composable
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
        containerColor = ArchivePalette.paper,
        topBar = { PageHeader(page = page, onPage = { page = it }) },
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
private fun PageHeader(page: AppPage, onPage: (AppPage) -> Unit) {
    Column(Modifier.fillMaxWidth().background(ArchivePalette.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "terminuke",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1.2).sp,
                    color = ArchivePalette.ink,
                )
                Text("PERSONAL REMOTE ARCHIVE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = ArchivePalette.muted)
            }
            Text("SSH / ANDROID", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ArchivePalette.copper)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PageTab("Hosts", page == AppPage.HOSTS, Modifier.weight(1f)) { onPage(AppPage.HOSTS) }
            PageTab("Keys", page == AppPage.KEYS, Modifier.weight(1f)) { onPage(AppPage.KEYS) }
            PageTab("Settings", page == AppPage.SETTINGS, Modifier.weight(1f)) { onPage(AppPage.SETTINGS) }
        }
        HorizontalDivider(color = ArchivePalette.rule)
    }
}

@Composable
private fun PageTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(
            label.uppercase(),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) ArchivePalette.ink else ArchivePalette.muted,
        )
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(if (selected) 2.dp else 1.dp).background(if (selected) ArchivePalette.copper else ArchivePalette.rule))
    }
}

@Composable
private fun HostList(
    hosts: List<HostEntity>, search: String, onSearch: (String) -> Unit,
    onAdd: () -> Unit, onConnect: (HostEntity) -> Unit, onEdit: (HostEntity) -> Unit,
    onDelete: (HostEntity) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ArchivePalette.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text("REMOTE ENDPOINTS", fontSize = 10.sp, letterSpacing = 1.8.sp, color = ArchivePalette.copper)
                Text("Host archive", fontSize = 37.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.3).sp, color = ArchivePalette.ink)
            }
            Text("${hosts.size.toString().padStart(2, '0')} / SAVED", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = ArchivePalette.muted)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                modifier = Modifier.weight(1f),
                label = { Text("SEARCH THE INDEX", letterSpacing = 1.sp) },
                singleLine = true,
            )
            Button(
                onClick = onAdd,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = ArchivePalette.graphite, contentColor = ArchivePalette.white),
            ) { Text("+ HOST", fontSize = 11.sp, letterSpacing = 1.sp) }
        }
        if (hosts.isEmpty()) {
            EmptyArchive(onAdd, Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(hosts, key = { _, host -> host.id }) { index, host ->
                    HostPlate(index + 1, host, onConnect = { onConnect(host) }, onEdit = { onEdit(host) }, onDelete = { onDelete(host) })
                }
            }
        }
    }
}

@Composable
private fun HostPlate(index: Int, host: HostEntity, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(color = ArchivePalette.graphite, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PLATE ${index.toString().padStart(2, '0')}  /  SSH", fontSize = 9.sp, letterSpacing = 1.5.sp, color = ArchivePalette.paleText)
                Spacer(Modifier.weight(1f))
                Text(":${host.port}", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ArchivePalette.paper)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                host.label,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.8).sp,
                color = ArchivePalette.white,
                maxLines = 1,
            )
            Text(host.hostname, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ArchivePalette.paleText)
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color(0xFF514F4B))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LOGIN", fontSize = 9.sp, letterSpacing = 1.3.sp, color = ArchivePalette.paleText)
                    Text(host.username, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ArchivePalette.white)
                }
                Column(Modifier.weight(1f)) {
                    Text("AUTH", fontSize = 9.sp, letterSpacing = 1.3.sp, color = ArchivePalette.paleText)
                    Text(host.authType.replace('-', ' ').uppercase(), fontSize = 10.sp, color = ArchivePalette.white)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ArchivePalette.paper, contentColor = ArchivePalette.ink),
                ) { Text("OPEN SESSION  ↗", fontSize = 11.sp, letterSpacing = 0.8.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onEdit) { Text("EDIT HOST", fontSize = 9.sp, letterSpacing = 1.2.sp, color = ArchivePalette.paleText) }
                TextButton(onClick = onDelete) { Text("REMOVE", fontSize = 9.sp, letterSpacing = 1.2.sp, color = ArchivePalette.paleText) }
            }
        }
    }
}

@Composable
private fun EmptyArchive(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = ArchivePalette.graphite, shape = RectangleShape) {
        Column(Modifier.padding(24.dp)) {
            Text("NO ENDPOINTS", fontSize = 10.sp, letterSpacing = 1.8.sp, color = ArchivePalette.paleText)
            Spacer(Modifier.height(12.dp))
            Text("Your remote\nworkspace begins here.", fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Light, color = ArchivePalette.white)
            Spacer(Modifier.height(8.dp))
            Text("Add a host to create your first connection plate.", color = ArchivePalette.paleText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = onAdd,
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchivePalette.paper),
            ) { Text("ADD FIRST HOST  ↗", fontSize = 11.sp, letterSpacing = 0.8.sp) }
        }
    }
}

@Composable
private fun HostEditorDialog(host: HostEntity?, keys: List<SshKeyEntity>, onDismiss: () -> Unit, onSave: (HostInput) -> Unit) {
    val maxFormHeight = LocalConfiguration.current.screenHeightDp.dp * 0.62f
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
            Column(
                modifier = Modifier.heightIn(max = maxFormHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorField("Label", label, { label = it }, errors["label"])
                EditorField("Hostname or IP", hostname, { hostname = it }, errors["hostname"])
                EditorField("Port", port, { port = it }, errors["port"], KeyboardType.Number)
                EditorField("Username", username, { username = it }, errors["username"])
                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                Column {
                    AuthenticationOption("Password", authType == "password") { authType = "password"; keyAlias = null }
                    AuthenticationOption("SSH key", authType == "key") { authType = "key"; keyAlias = keys.firstOrNull()?.alias }
                    AuthenticationOption("Keyboard-interactive / OTP", authType == "keyboard-interactive") {
                        authType = "keyboard-interactive"
                        keyAlias = null
                    }
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
        window?.statusBarColor = ArchivePalette.graphite.toArgb()
        window?.navigationBarColor = ArchivePalette.black.toArgb()
        window?.let { WindowInsetsControllerCompat(it, it.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        } }
        onDispose {
            if (keepScreenOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.statusBarColor = ArchivePalette.paper.toArgb()
            window?.navigationBarColor = ArchivePalette.paper.toArgb()
            window?.let { WindowInsetsControllerCompat(it, it.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            } }
        }
    }
    Column(Modifier.fillMaxSize().background(ArchivePalette.black)) {
        Row(
            Modifier.fillMaxWidth().background(ArchivePalette.graphite).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("LIVE SESSION  /  ${host.port}", fontSize = 9.sp, letterSpacing = 1.6.sp, color = ArchivePalette.paleText)
                Text("${host.username}@${host.hostname}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ArchivePalette.white)
            }
            TextButton(onClick = onDisconnect) {
                Text("DISCONNECT", fontSize = 10.sp, letterSpacing = 0.8.sp, color = ArchivePalette.paper)
            }
        }
        AndroidView(
            factory = { bridge.terminalView },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            update = {
                val metrics = context.resources.displayMetrics
                val pixelSize = terminalFontSizePixels(fontSize, metrics.density, context.resources.configuration.fontScale)
                it.setTextSize(pixelSize)
            },
        )
        Row(
            Modifier.fillMaxWidth().background(ArchivePalette.graphite).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL-C" to "\u0003", "CTRL-D" to "\u0004", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "|" to "|", "~" to "~")
                .forEach { (label, value) ->
                    OutlinedButton(
                        onClick = { bridge.send(value.toByteArray()) },
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Color(0xFF625F59)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchivePalette.paper),
                    ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
        }
    }
}

@Composable
private fun KeyList(keys: List<SshKeyEntity>, onGenerate: () -> Unit, onGenerateRsa: () -> Unit, onImport: () -> Unit, onDelete: (SshKeyEntity) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(ArchivePalette.paper).padding(horizontal = 22.dp)) {
        Column(Modifier.padding(top = 22.dp, bottom = 14.dp)) {
            Text("CREDENTIALS / LOCAL KEYRING", fontSize = 10.sp, letterSpacing = 1.6.sp, color = ArchivePalette.copper)
            Text("Key archive", fontSize = 37.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.3).sp, color = ArchivePalette.ink)
            Text("Private keys stay encrypted on this device.", color = ArchivePalette.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            OutlinedButton(onClick = onGenerate, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
                Text("GENERATE ED25519 KEY  ↗", fontSize = 11.sp, letterSpacing = 0.8.sp)
            }
            OutlinedButton(onClick = onGenerateRsa, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
                Text("GENERATE RSA-3072 KEY  ↗", fontSize = 11.sp, letterSpacing = 0.8.sp)
            }
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = ArchivePalette.graphite, contentColor = ArchivePalette.paper),
            ) { Text("IMPORT PEM KEY", fontSize = 11.sp, letterSpacing = 0.8.sp) }
        }
        Text("${keys.size.toString().padStart(2, '0')} KEYS STORED", fontSize = 9.sp, letterSpacing = 1.5.sp, color = ArchivePalette.muted, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(keys, key = { it.alias }) { key ->
                Surface(Modifier.fillMaxWidth().border(1.dp, ArchivePalette.rule), color = ArchivePalette.white, shape = RectangleShape) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.keyType.uppercase(), fontSize = 9.sp, letterSpacing = 1.3.sp, color = ArchivePalette.copper)
                            Text(key.name, fontSize = 20.sp, fontWeight = FontWeight.Light, color = ArchivePalette.ink)
                            Text(key.publicOpenSsh.ifBlank { "IMPORTED / PUBLIC PREVIEW UNAVAILABLE" }, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = ArchivePalette.muted, maxLines = 2)
                        }
                        TextButton(onClick = { onDelete(key) }) { Text("REMOVE", fontSize = 10.sp, letterSpacing = 0.8.sp, color = ArchivePalette.copper) }
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
    Column(modifier.fillMaxSize().background(ArchivePalette.paper).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Column(Modifier.padding(top = 22.dp, bottom = 18.dp)) {
            Text("DEVICE / DISPLAY", fontSize = 10.sp, letterSpacing = 1.6.sp, color = ArchivePalette.copper)
            Text("Preferences", fontSize = 37.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.3).sp, color = ArchivePalette.ink)
        }
        Surface(Modifier.fillMaxWidth(), color = ArchivePalette.white, shape = RectangleShape) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TERMINAL TYPE", fontSize = 9.sp, letterSpacing = 1.4.sp, color = ArchivePalette.muted)
                Text("XTERM-256COLOR  /  UTF-8", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ArchivePalette.ink)
                HorizontalDivider(color = ArchivePalette.rule, modifier = Modifier.padding(vertical = 6.dp))
                Text("TYPE SIZE    ${settings.fontSizeSp} SP", fontSize = 10.sp, letterSpacing = 1.sp, color = ArchivePalette.ink)
                Slider(value = settings.fontSizeSp.toFloat(), onValueChange = { onFontSize(it.toInt()) }, valueRange = 12f..24f, steps = 11)
                SettingSwitch("Keep screen awake while connected", settings.keepScreenOn, onKeepScreenOn)
                SettingSwitch("Hide terminal in screenshots and Recents", settings.secureFlag, onSecureFlag)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth().padding(bottom = 24.dp), color = ArchivePalette.graphite, shape = RectangleShape) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OFFLINE BACKUP", fontSize = 9.sp, letterSpacing = 1.5.sp, color = ArchivePalette.paleText)
                Text("Hosts only.\nNo secrets exported.", fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Light, color = ArchivePalette.white)
                Text("Android backup is disabled. Passwords and private keys are never included in this file.", color = ArchivePalette.paleText, style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = ArchivePalette.paper, contentColor = ArchivePalette.ink)) { Text("EXPORT HOSTS", fontSize = 10.sp, letterSpacing = 0.8.sp) }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RectangleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = ArchivePalette.paper)) { Text("IMPORT HOSTS", fontSize = 10.sp, letterSpacing = 0.8.sp) }
                }
            }
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
private fun AuthenticationOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TerminukeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ArchivePalette.graphite,
            onPrimary = ArchivePalette.paper,
            secondary = ArchivePalette.copper,
            background = ArchivePalette.paper,
            onBackground = ArchivePalette.ink,
            surface = ArchivePalette.white,
            onSurface = ArchivePalette.ink,
            surfaceVariant = Color(0xFFDED7CB),
            onSurfaceVariant = ArchivePalette.muted,
            outline = ArchivePalette.rule,
            error = Color(0xFF9A4C3D),
        ),
        content = content,
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
