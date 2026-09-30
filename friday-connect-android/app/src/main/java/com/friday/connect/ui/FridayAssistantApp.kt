package com.friday.connect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.friday.connect.assistant.*
import com.friday.connect.core.AgentStateStore
import com.friday.connect.core.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridayAssistantApp(cameraGranted: Boolean, notificationsGranted: Boolean, onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit, onStartService: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { AssistantEngine.get(context) }
    val scope = rememberCoroutineScope()
    val conversation by engine.conversation.collectAsState()
    val history by engine.repository.conversations.collectAsState()
    val busy by engine.busy.collectAsState()
    val replyText by engine.lastReply.collectAsState()
    val progress by engine.progress.collectAsState()
    val confirmation by engine.confirmation.collectAsState()
    val voiceStatus by VoiceBus.status.collectAsState()
    val voiceActive by VoiceBus.active.collectAsState()
    val voicePaused by VoiceBus.paused.collectAsState()
    val transcript by VoiceBus.transcript.collectAsState()
    val interrupted by VoiceBus.interrupt.collectAsState()
    val connection by AgentStateStore.connectionState.collectAsState()
    val coverage = remember { runCatching { JSONArray(context.assets.open("feature-coverage.json").bufferedReader().use { it.readText() }) }.getOrDefault(JSONArray()) }
    val credential by AgentStateStore.credential.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Chat") }
    var legacy by rememberSaveable { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var chatTts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val speaker = android.speech.tts.TextToSpeech(context) { ttsReady = it == android.speech.tts.TextToSpeech.SUCCESS }
        chatTts = speaker
        onDispose { speaker.stop(); speaker.shutdown() }
    }
    LaunchedEffect(replyText, voiceActive, ttsReady) {
        if (replyText.isNotBlank() && replyText != lastSpoken) {
            lastSpoken = replyText
            if (!voiceActive && ttsReady && engine.settings.setting("speak", "true") == "true") {
                val locale = if (Regex("[\\u0900-\\u097F]").containsMatchIn(replyText)) java.util.Locale.forLanguageTag("hi-IN") else java.util.Locale.forLanguageTag("en-IN")
                if ((chatTts?.setLanguage(locale) ?: -1) >= 0) chatTts?.speak(replyText.take(3900), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "chat-reply")
            }
        }
    }
    LaunchedEffect(busy) { if (busy) { chatTts?.stop(); lastSpoken = "" } }
    LaunchedEffect(interrupted) { chatTts?.stop() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) pendingPermission?.invoke() else error = "Permission declined. You can enable this feature later."
        pendingPermission = null
    }
    fun withPermissions(required: List<String>, action: () -> Unit) {
        val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPermission = action; permission.launch(missing.toTypedArray()) }
    }
    fun voice(action: String) {
        val permissions = listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        withPermissions(permissions) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FridayVoiceService::class.java).setAction(action)) }
                .onFailure { error = "Unable to start voice. Keep Friday open and check microphone permission." }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        runCatching { withContext(Dispatchers.IO) { engine.repository.importAttachment(uri) } }.onSuccess {
            if (attachments.size < 3) attachments = attachments + it else { File(it.path).delete(); error = "Maximum three attachments per message" }
        }.onFailure { error = it.message }
    } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val folder = File(context.filesDir, "attachments").apply { mkdirs() }; val file = File(folder, UUID.randomUUID().toString())
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            if (attachments.size < 3) attachments = attachments + Attachment("Camera photo", "image/jpeg", file.absolutePath) else file.delete()
            tab = "Chat"
        }
    }
    fun shareConversation() {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }; val file = File(folder, "Friday-conversation.txt")
        file.writeText(conversation.messages.joinToString("\n\n") { "${it.role}: ${it.text}" })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share conversation"))
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("FRIDAY", fontWeight = FontWeight.Bold)
            Text(if (busy) progress else "Your personal AI assistant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) } },
            actions = { if (busy) TextButton(onClick = { engine.cancel() }) { Text("Stop") }
                TextButton(onClick = { engine.newConversation(); attachments.forEach { File(it.path).delete() }; attachments = emptyList(); tab = "Chat" }, enabled = !busy) { Text("New") } }) },
        bottomBar = { NavigationBar { listOf("Chat" to "◌", "Voice" to "◎", "Tools" to "◇", "PC" to "▣", "Settings" to "⚙").forEach { (name, glyph) ->
            NavigationBarItem(selected = tab == name, onClick = { tab = name; legacy = false }, icon = { Text(glyph) }, label = { Text(name) }) } } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            HolographicBackground()
            when (tab) {
                "Chat" -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { historyOpen = true }) { Text("Conversations (${history.size})") }
                        TextButton(onClick = { shareConversation() }, enabled = conversation.messages.isNotEmpty()) { Text("Share") }
                    }
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(conversation.messages.size, conversation.messages.lastOrNull()?.text?.length) {
                        if (conversation.messages.isNotEmpty()) listState.scrollToItem(conversation.messages.lastIndex)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (conversation.messages.isEmpty()) item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f))) {
                                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Good to see you.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text("Ask, create, or get something done. Friday works on your phone even when your PC is off.")
                                    if (engine.settings.setting("gemini_key").isBlank()) Button(onClick = { tab = "Settings" }) { Text("Set up AI key") }
                                    Text("Phone tools work offline. Cloud answers need internet.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        items(conversation.messages, key = { it.id }) { m ->
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor =
                                if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .95f))) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (m.role == "user") "YOU" else "FRIDAY · ${m.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    androidx.compose.foundation.text.selection.SelectionContainer { Text(m.text.ifBlank { "Thinking…" }) }
                                    m.attachments.forEach { Text("Attached · ${it.name}", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                    attachments.forEach { a -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { attachments = attachments - a; File(a.path).delete() }) { Text("Remove") }
                    } }
                    var input by rememberSaveable { mutableStateOf("") }
                    fun send() { runCatching { engine.send(input, attachments); input = ""; attachments = emptyList() }.onFailure { error = it.message } }
                    OutlinedTextField(input, { input = it.take(20000) }, Modifier.fillMaxWidth(), placeholder = { Text("Message Friday…") }, maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (!busy) send() }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { picker.launch(arrayOf("image/*", "text/*", "application/pdf", "application/json")) }, enabled = !busy && attachments.size < 3) { Text("Attach") }
                        TextButton(onClick = { withPermissions(listOf(Manifest.permission.CAMERA)) { camera.launch(null) } }, enabled = !busy && attachments.size < 3) { Text("Camera") }
                        TextButton(onClick = { voice(FridayVoiceService.TAP) }) { Text("Mic") }
                        Button(onClick = { send() }, enabled = !busy && (input.isNotBlank() || attachments.isNotEmpty())) { Text("Send") }
                    }
                }
                "Voice" -> Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◎", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.secondary)
                    Text("Friday is listening", style = MaterialTheme.typography.headlineMedium)
                    Text(voiceStatus)
                    Text("Say “Friday”, “wake up Friday”, or “utho Friday”. Choose the Hindi voice model in Settings for Hindi speech.")
                    Text("Wake listening runs offline. Only the activated command is sent for an AI answer. The microphone notification stays visible.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { voice(FridayVoiceService.START) }, enabled = !voiceActive) { Text("Enable hands-free") }
                    Button(onClick = { voice(FridayVoiceService.TAP) }) { Text("Tap to talk") }
                    if (voiceActive) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { voice(if (voicePaused) FridayVoiceService.RESUME else FridayVoiceService.PAUSE) }) { Text(if (voicePaused) "Resume" else "Pause") }
                        OutlinedButton(onClick = { context.stopService(Intent(context, FridayVoiceService::class.java)) }) { Text("Stop voice") }
                    }
                    OutlinedButton(onClick = { engine.cancel() }) { Text("Interrupt / stop task") }
                    if (transcript.isNotBlank()) Card { Text(transcript, Modifier.padding(16.dp)) }
                    Text("After a reboot, open Friday and enable listening again. Accuracy and battery use depend on your phone.", style = MaterialTheme.typography.bodySmall)
                }
                "Tools" -> LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Phone tools", style = MaterialTheme.typography.headlineMedium) }
                    item { Text("Permissions are optional. Enable only the features you use.") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notifications") }
                    } }
                    item { OutlinedButton(onClick = { engine.cancel(); com.friday.connect.commands.AutomationGuard.stop() }) { Text("Stop all automation") } }
                    items(listOf("get_battery", "get_device_info", "volume_get", "flashlight_on", "flashlight_off", "list_installed_apps", "list_reminders", "read_screen_text", "read_notifications")) { name ->
                        val def = PhoneToolCatalog.definitions.first { it.name == name }
                        Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(name.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                Text(def.description, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { withPermissions(def.permissions.filter { it.startsWith("android.permission") && (it != Manifest.permission.POST_NOTIFICATIONS || Build.VERSION.SDK_INT >= 33) }) { engine.runTool(name) } }, enabled = !busy) { Text("Run") }
                        } }
                    }
                    item { Text("Ask Friday to open apps, adjust volume, set reminders, prepare calendar events or messages, and control media. Accessibility steps require approval.") }
                    item { Text("Desktop feature coverage", style = MaterialTheme.typography.titleLarge) }
                    items((0 until coverage.length()).map { coverage.getJSONObject(it) }) { item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Text(item.getString("name").replace('_', ' '), fontWeight = FontWeight.SemiBold)
                        Text(item.getString("target"), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                        Text(item.getString("note"), style = MaterialTheme.typography.bodySmall)
                    } } }
                    item { Card { Text("Unavailable on Android: bypassing a phone lock, changing protected system settings without user approval, installing Windows software, and self-patching this APK. Windows and Python features run on the paired PC.", Modifier.padding(16.dp)) } }
                }
                "PC" -> if (legacy) Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { legacy = false }) { Text("Back to PC overview") }
                    Box(Modifier.weight(1f)) { FridayConnectApp(cameraGranted, notificationsGranted, onRequestCameraPermission, onRequestNotificationPermission, onStartService) }
                } else Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Your desktop, connected", style = MaterialTheme.typography.headlineMedium)
                    Text(if (credential == null) "No PC paired" else "${credential!!.deviceName} · ${connection.name.lowercase()}")
                    Text("Connect to Windows tools, browser automation, files, coding, and document generation. Independent phone chat continues when the PC is off.")
                    Button(onClick = { legacy = true }) { Text(if (credential == null) "Pair a PC" else "Connection & PC controls") }
                    var command by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(command, { command = it }, label = { Text("Task for desktop Friday") }, modifier = Modifier.fillMaxWidth())
                    attachments.forEach { a -> Text("Transfer attachment: ${a.name}") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "text/*", "application/pdf", "application/json")) }, enabled = attachments.size < 3 && !busy) { Text("Attach file for PC") }
                    Button(onClick = { engine.runTool("pc_task", JSONObject().put("text", command), attachments); attachments = emptyList() }, enabled = credential != null && command.isNotBlank() && !busy) { Text("Review & send to PC") }
                    Text("Remote access requires a secure HTTPS tunnel. Local pairing can use a private LAN IP. Commands are never queued for automatic replay.", style = MaterialTheme.typography.bodySmall)
                    Text("PC tasks report dispatch status. Once dispatched, stopping the phone cannot guarantee the Windows task has stopped.", style = MaterialTheme.typography.bodySmall)
                }
                "Settings" -> AssistantSettings(engine, voiceActive, onError = { error = it })
            }
        }
    }
    if (confirmation != null) AlertDialog(onDismissRequest = { engine.approve(false) }, title = { Text(confirmation!!.title) },
        text = { Text(confirmation!!.detail, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { engine.approve(true) }) { Text("Allow once") } },
        dismissButton = { TextButton(onClick = { engine.approve(false) }) { Text("Cancel") } })
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Friday") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("Saved conversations") }, text = {
        LazyColumn { items(history, key = { it.id }) { c -> TextButton(onClick = { if (!busy) { engine.select(c.id); historyOpen = false } }) { Text(c.title) } } }
    }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Close") } }, dismissButton = {
        TextButton(onClick = { engine.deleteCurrent(); historyOpen = false }, enabled = !busy) { Text("Delete current") }
    })
}

@Composable
private fun AssistantSettings(engine: AssistantEngine, voiceActive: Boolean, onError: (String) -> Unit) {
    val s = engine.settings
    var key by remember { mutableStateOf("") }; var routerKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(s.setting("gemini_model", "gemini-2.5-flash")) }
    var routerModel by remember { mutableStateOf(s.setting("openrouter_model", "google/gemini-2.5-flash")) }
    var memory by remember { mutableStateOf(s.setting("memory")) }
    var language by remember { mutableStateOf(s.setting("voice_language", "en")) }
    var speak by remember { mutableStateOf(s.setting("speak", "true") == "true") }
    var saved by remember { mutableStateOf(false) }
    var keysPresent by remember { mutableStateOf(s.setting("gemini_key").isNotBlank()) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Make Friday yours", style = MaterialTheme.typography.headlineMedium)
        Text("No account needed. Your provider keys are encrypted with Android Keystore protection. AI requests use your provider quota.")
        OutlinedTextField(key, { key = it; saved = false }, label = { Text(if (keysPresent) "Gemini key saved · enter to replace" else "Gemini API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(model, { model = it; saved = false }, label = { Text("Gemini model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(routerKey, { routerKey = it; saved = false }, label = { Text("Optional OpenRouter key · enter to replace") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(routerModel, { routerModel = it; saved = false }, label = { Text("OpenRouter fallback model") }, modifier = Modifier.fillMaxWidth())
        Text("Fallback runs only before any answer or action. PDFs require Gemini. Attachments and approved screen/notification text are sent to your chosen provider.", style = MaterialTheme.typography.bodySmall)
        Text("Offline voice model", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == "en", onClick = { language = "en"; saved = false }, label = { Text("English / Hinglish") }, enabled = !voiceActive)
            FilterChip(selected = language == "hi", onClick = { language = "hi"; saved = false }, label = { Text("हिन्दी") }, enabled = !voiceActive)
        }
        if (voiceActive) Text("Stop voice before changing its recognition language.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Speak replies", Modifier.weight(1f)); Switch(speak, { speak = it; saved = false }) }
        OutlinedTextField(memory, { memory = it.take(12000); saved = false }, label = { Text("Personal memory & preferences") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
        Text("Memory stays on this phone and is included in AI requests. It is separate from desktop memory.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (key.isNotBlank()) s.saveSetting("gemini_key", key.trim())
            if (routerKey.isNotBlank()) s.saveSetting("openrouter_key", routerKey.trim())
            s.saveSetting("gemini_model", model.trim()); s.saveSetting("openrouter_model", routerModel.trim())
            s.saveSetting("memory", memory); s.saveSetting("voice_language", language); s.saveSetting("speak", speak.toString())
            key = ""; routerKey = ""; keysPresent = s.setting("gemini_key").isNotBlank(); saved = true
        }) { Text(if (saved) "Saved" else "Save settings") }
        OutlinedButton(onClick = { s.saveSetting("gemini_key", ""); s.saveSetting("openrouter_key", ""); key = ""; routerKey = ""; keysPresent = false; saved = false }) { Text("Remove provider keys") }
        Text("Friday AI 1.0 · Created by Gaurav", style = MaterialTheme.typography.labelSmall)
    }
}
