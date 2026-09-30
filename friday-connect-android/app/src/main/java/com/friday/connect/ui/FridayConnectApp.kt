package com.friday.connect.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.friday.connect.FridayConnectForegroundService
import com.friday.connect.core.AgentStateStore
import com.friday.connect.core.ConnectionState
import com.friday.connect.core.DeviceCredential
import com.friday.connect.core.GatewayEndpoint
import com.friday.connect.core.PairingOffer
import com.friday.connect.network.FridayGatewayDiscovery
import com.friday.connect.pairing.PairingPayloadParser
import com.friday.connect.pairing.PairingStorage
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.foundation.horizontalScroll
import android.widget.Toast
import android.provider.Settings
import com.friday.connect.accessibility.FridayAccessibilityService
import com.friday.connect.commands.DeviceCommandHandler
import com.friday.connect.network.RemotePcClient
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send



@Composable
fun HolographicBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF11182A), androidx.compose.ui.graphics.Color(0xFF070A12))))
        drawCircle(
            brush = Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color(0x332C7DFF), androidx.compose.ui.graphics.Color.Transparent)),
            radius = size.maxDimension * 0.72f,
            center = Offset(size.width * 0.83f, size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color(0x225E5CE6), androidx.compose.ui.graphics.Color.Transparent)),
            radius = size.maxDimension * 0.62f,
            center = Offset(size.width * 0.12f, size.height * 0.82f)
        )
        val spacing = 28.dp.toPx()
        for (x in 0..(size.width / spacing).toInt()) {
            for (y in 0..(size.height / spacing).toInt()) {
                drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.025f), 0.8.dp.toPx(), Offset(x * spacing, y * spacing))
            }
        }
    }
}

@Composable
fun FridayConnectApp(
    cameraGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val discovery = remember { FridayGatewayDiscovery(context) }
    val storage = remember { PairingStorage(context) }
    val connectionState by AgentStateStore.connectionState.collectAsState()
    val gateway by AgentStateStore.gateway.collectAsState()
    val credential by AgentStateStore.credential.collectAsState()
    val pairingOffer by AgentStateStore.pairingOffer.collectAsState()
    val status by AgentStateStore.statusText.collectAsState()
    val lastError by AgentStateStore.lastError.collectAsState()
    val logs by AgentStateStore.logs.collectAsState()

    var showManual by rememberSaveable { mutableStateOf(false) }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualPort by rememberSaveable { mutableStateOf("8765") }
    var manualCode by rememberSaveable { mutableStateOf("") }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
    }

    val setupMissing = listOfNotNull(
        if (!cameraGranted) "Camera" else null,
        if (!notificationsGranted) "Notifications" else null,
    )

    val navController = rememberNavController()

    // Determine start destination
    val startDest = when {
        setupMissing.isNotEmpty() -> "permissions"
        credential != null -> "home"
        else -> "connect"
    }

    LaunchedEffect(credential) {
        if (credential != null && navController.currentDestination?.route != "home" && navController.currentDestination?.route != "connected_anim") {
            navController.navigate("connected_anim") {
                popUpTo(0)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Render 3D Background
        HolographicBackground()

        // UI Overlay
        NavHost(
            navController = navController, 
            startDestination = startDest,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(500)) },
            exitTransition = { fadeOut(tween(500)) }
        ) {
            composable("permissions") {
                StartupPermissionsScreen(
                    missingItems = setupMissing,
                    cameraGranted = cameraGranted,
                    notificationsGranted = notificationsGranted,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onContinue = { 
                        if (credential != null) navController.navigate("home") { popUpTo(0) }
                        else navController.navigate("connect") { popUpTo(0) }
                    }
                )
            }
            composable("welcome") {
                WelcomeScreen(
                    onNext = { navController.navigate("about") }
                )
            }
            composable("about") {
                AboutScreen(
                    onNext = { navController.navigate("connect") }
                )
            }
            composable("connect") {
                if (pairingOffer != null) {
                    PairingPendingScreen(
                        offer = pairingOffer!!,
                        gateway = gateway,
                        state = connectionState,
                        status = status,
                        error = lastError,
                        onRequestPairing = onStartService,
                        onCancel = {
                            storage.clearGatewayHint()
                            AgentStateStore.setPairingOffer(null)
                        },
                        onManualEntry = { showManual = true },
                        onScanAgain = {
                            storage.clearGatewayHint()
                            AgentStateStore.setPairingOffer(null)
                            if (!cameraGranted) onRequestCameraPermission()
                            navController.navigate("scanner")
                        }
                    )
                } else {
                    ConnectChoiceScreen(
                        onScanQr = { 
                            if (!cameraGranted) onRequestCameraPermission()
                            navController.navigate("scanner") 
                        },
                        onEnterIp = { showManual = true },
                        onFindLocal = {
                            discovery.start(
                                onFound = {
                                    AgentStateStore.setGateway(it)
                                    AgentStateStore.setStatus("Gateway Online")
                                    onStartService()
                                },
                                onError = { AgentStateStore.setError(it) }
                            )
                        }
                    )
                }
            }
            composable("scanner") {
                QrScannerScreen(
                    onBack = { navController.popBackStack() },
                    onRequestPermission = onRequestCameraPermission,
                    errorText = scanError,
                    onManualEntry = { showManual = true },
                    onScanned = { raw ->
                        val offer = PairingPayloadParser.parse(raw)
                        if (offer == null) {
                            scanError = "Unrecognized QR format. Please scan Friday pairing code."
                            AgentStateStore.setError(scanError)
                        } else {
                            scanError = null
                            storage.saveGatewayHint(offer)
                            AgentStateStore.setPairingOffer(offer)
                            AgentStateStore.setGateway(
                                GatewayEndpoint(
                                    name = "Friday PC",
                                    host = offer.host,
                                    port = offer.port,
                                    tunnelUrl = offer.tunnelUrl
                                )
                            )
                            AgentStateStore.setStatus("Pairing payload loaded")
                            onStartService()
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable("connected_anim") {
                ConnectedAnimatedScreen(
                    onFinished = { navController.navigate("home") { popUpTo(0) } }
                )
            }
            composable("home") {
                if (credential != null) {
                    ConnectedScreen(
                        credential = credential!!,
                        gateway = gateway,
                        state = connectionState,
                        logs = logs,
                        status = status,
                        onDisconnect = {
                            context.startService(Intent(context, FridayConnectForegroundService::class.java).apply {
                                action = FridayConnectForegroundService.ACTION_STOP
                            })
                            navController.navigate("welcome") { popUpTo(0) }
                        },
                        onOpenPermissions = {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        onOpenAutopilotSettings = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        },
                        onOpenChat = { navController.navigate("chat") }
                    )
                }
            }
            composable("chat") {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onSendMessage = { text ->
                        val intent = Intent(context, FridayConnectForegroundService::class.java).apply {
                            action = FridayConnectForegroundService.ACTION_SEND_CHAT
                            putExtra(FridayConnectForegroundService.EXTRA_CHAT_TEXT, text)
                        }
                        context.startService(intent)
                    }
                )
            }
        }

        if (showManual) {
            ManualConnectDialog(
                host = manualHost,
                port = manualPort,
                pairingCode = manualCode,
                onHostChange = { manualHost = it },
                onPortChange = { manualPort = it },
                onPairingCodeChange = { manualCode = it },
                onDismiss = { showManual = false },
                onConnect = {
                    val rawHost = manualHost.trim()
                    val rawPort = manualPort.toIntOrNull() ?: 8765
                    val code = manualCode.trim()

                    var resolvedHost = rawHost
                    var resolvedPort = rawPort
                    var resolvedTunnel: String? = null

                    if (rawHost.startsWith("http://") || rawHost.startsWith("https://")) {
                        resolvedTunnel = rawHost.trimEnd('/')
                        try {
                            val uri = android.net.Uri.parse(rawHost)
                            resolvedHost = uri.host ?: rawHost
                            resolvedPort = if (uri.port > 0) uri.port else if (rawHost.startsWith("https://")) 443 else 8765
                        } catch (_: Exception) {}
                    } else if (rawHost.contains("trycloudflare.com") || rawHost.contains(".loca.lt") || rawHost.contains(".ngrok")) {
                        resolvedTunnel = "https://${rawHost.trimEnd('/')}"
                        resolvedHost = rawHost
                        resolvedPort = 443
                    }

                    val offer = PairingOffer(
                        service = "_FRIDAY._tcp.local.",
                        host = resolvedHost,
                        port = resolvedPort,
                        pairingToken = "",
                        pairingCode = code,
                        expiresInSeconds = 300,
                        createdAt = java.time.Instant.now().toString(),
                        tunnelUrl = resolvedTunnel
                    )

                    storage.saveGatewayHint(offer)
                    AgentStateStore.setPairingOffer(offer)
                    AgentStateStore.setGateway(
                        GatewayEndpoint(
                            name = "Friday PC",
                            host = resolvedHost,
                            port = resolvedPort,
                            tunnelUrl = resolvedTunnel
                        )
                    )
                    AgentStateStore.setStatus("Connecting to Friday PC...")
                    onStartService()
                    showManual = false
                }
            )
        }
    }
}

// --- NEW ONBOARDING SCREENS ---

@Composable
fun GlassCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f))
    ) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
fun WelcomeScreen(onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FRIDAY", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("CONNECT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = androidx.compose.ui.graphics.Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Created by Gaurav", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(48.dp))
        GlassCard {
            Text("Tactical Mobile Interface", style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.White)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text("Initialize System")
            }
        }
    }
}

@Composable
fun AboutScreen(onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCard {
            Text("Tactical Hardware Bridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Created and Engineered by Gaurav", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("This agent acts as a secure physical bridge between your Android device and Friday AI.", color = androidx.compose.ui.graphics.Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text("Next")
            }
        }
    }
}

@Composable
fun ConnectChoiceScreen(onScanQr: () -> Unit, onEnterIp: () -> Unit, onFindLocal: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCard {
            Text("Pair Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) { Text("Scan QR Code") }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onFindLocal, modifier = Modifier.fillMaxWidth()) { Text("Auto Discover") }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onEnterIp) { Text("Enter IP Manually", color = androidx.compose.ui.graphics.Color.White) }
        }
    }
}

@Composable
fun ConnectedAnimatedScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onFinished()
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCard {
            Text("Connection Established", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}


@Composable
private fun StartupPermissionsScreen(
    missingItems: List<String>,
    cameraGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).background(androidx.compose.ui.graphics.Color.Transparent).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SETUP REQUIRED", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text("Enable the permissions Friday Connect needs to control your phone reliably.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), contentColor = androidx.compose.ui.graphics.Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
            Column(Modifier.padding(18.dp)) {
                Text("Missing permissions", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                PermissionRow("Camera", cameraGranted)
                PermissionRow("Notifications", notificationsGranted)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!cameraGranted) {
            Button(onClick = onRequestCameraPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow Camera") }
            Spacer(Modifier.height(10.dp))
        }
        if (!notificationsGranted) {
            Button(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow Notifications") }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (missingItems.isEmpty()) "Continue" else "Skip / Continue Anyway")
        }
        val ctx = LocalContext.current
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", ctx.packageName, null)
                }
                ctx.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open App Permissions in System Settings", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (granted) "Granted" else "Needed", color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DiscoveryScreen(
    gateway: GatewayEndpoint?,
    status: String,
    error: String?,
    onConnect: (() -> Unit)?,
    onFindFriday: () -> Unit,
    onScanQr: () -> Unit,
    onEnterIp: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).background(androidx.compose.ui.graphics.Color.Transparent).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("FRIDAY CONNECT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = androidx.compose.ui.graphics.Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Created by Gaurav", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
        Text("Connect this device to Friday AI.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), contentColor = androidx.compose.ui.graphics.Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
            Column(Modifier.padding(20.dp)) {
                Text("Connection", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(gateway?.let { "${it.name} · ${it.host}:${it.port}" } ?: "No gateway selected")
                Spacer(Modifier.height(4.dp))
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (gateway != null && onConnect != null) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onFindFriday, modifier = Modifier.fillMaxWidth()) { Text("Find Friday") }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onEnterIp, modifier = Modifier.fillMaxWidth()) { Text("Enter IP") }
    }
}

@Composable
private fun PairingPendingScreen(
    offer: PairingOffer,
    gateway: GatewayEndpoint?,
    state: ConnectionState,
    status: String,
    error: String?,
    onRequestPairing: () -> Unit,
    onCancel: () -> Unit,
    onManualEntry: () -> Unit,
    onScanAgain: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PAIR WITH FRIDAY", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!offer.tunnelUrl.isNullOrBlank()) "Global Cloudflare Tunnel" else "Local Network Connection",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), contentColor = androidx.compose.ui.graphics.Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PAIRING CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = offer.pairingCode.ifBlank { "READY" },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))
                Spacer(Modifier.height(12.dp))
                Text("${gateway?.name ?: "Friday PC"}", fontWeight = FontWeight.Bold)
                val target = if (!offer.tunnelUrl.isNullOrBlank()) offer.tunnelUrl!! else "${offer.host}:${offer.port}"
                Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("State: $state", style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequestPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Pair Now")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
            Text("Edit / Enter IP & Code Manually")
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Different QR Code")
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun FridayVoiceReactorOrb(
    isListening: Boolean,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reactorTransition")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 1800 else 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactorRotation"
    )
    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 2500 else 8500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactorCounterRotation"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.10f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 500 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reactorPulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clickable { onOrbClick() },
            contentAlignment = Alignment.Center
        ) {
            // Animated Canvas for HUD rings and orbiting particle dots
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = this.center
                val radius = size.minDimension / 2.0f

                // Outer faint dotted track
                drawCircle(
                    color = androidx.compose.ui.graphics.Color(0xFF00FFCC).copy(alpha = 0.15f),
                    radius = radius - 10.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Rotating arc 1 with glowing cyan
                drawArc(
                    color = androidx.compose.ui.graphics.Color(0xFF00FFCC).copy(alpha = if (isListening) 0.95f else 0.7f),
                    startAngle = rotation,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                // Orbital dots
                val orbitRadius = radius - 10.dp.toPx()
                for (i in 0..4) {
                    val angleRad = Math.toRadians((rotation + i * 18.0).toDouble())
                    val x = centerOffset.x + orbitRadius * cos(angleRad).toFloat()
                    val y = centerOffset.y + orbitRadius * sin(angleRad).toFloat()
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color(0xFF00FFCC).copy(alpha = 0.85f - (i * 0.15f)),
                        radius = (4.0f - i * 0.5f).coerceAtLeast(1.5f).dp.toPx(),
                        center = Offset(x, y)
                    )
                }

                // Counter-rotating arc 2
                drawArc(
                    color = androidx.compose.ui.graphics.Color(0xFF0284C7).copy(alpha = if (isListening) 0.85f else 0.45f),
                    startAngle = counterRotation,
                    sweepAngle = 80f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                for (i in 0..3) {
                    val angleRad = Math.toRadians((counterRotation + i * 20.0).toDouble())
                    val x = centerOffset.x + orbitRadius * cos(angleRad).toFloat()
                    val y = centerOffset.y + orbitRadius * sin(angleRad).toFloat()
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color(0xFF38BDF8).copy(alpha = 0.75f),
                        radius = 2.5.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Central Core Orb with pulse
            Surface(
                modifier = Modifier
                    .size(90.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape),
                color = androidx.compose.ui.graphics.Color(0xFF061418),
                shape = androidx.compose.foundation.shape.CircleShape,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    if (isListening) androidx.compose.ui.graphics.Color(0xFF00FFCC) else androidx.compose.ui.graphics.Color(0xFF0891B2)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "FRIDAY",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = if (isListening) androidx.compose.ui.graphics.Color(0xFF00FFCC) else androidx.compose.ui.graphics.Color(0xFF38BDF8),
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (isListening) androidx.compose.ui.graphics.Color(0xFF00FFCC) else androidx.compose.ui.graphics.Color(0xFF0891B2))
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (isListening) "🎙️ LISTENING TO YOUR VOICE..." else "TAP ORB TO START LISTENING",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isListening) androidx.compose.ui.graphics.Color(0xFF00FFCC) else MaterialTheme.colorScheme.secondary,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun LegacyConnectedScreen(
    credential: DeviceCredential,
    gateway: GatewayEndpoint?,
    state: ConnectionState,
    logs: List<String>,
    status: String,
    onDisconnect: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAutopilotSettings: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val remoteClient = remember { RemotePcClient(context) { credential } }
    val commandHandler = remember { DeviceCommandHandler(context) }

    // Selected Tab: 0 = PC Remote, 1 = Phone Autopilot, 2 = AI & Logs
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // Voice & Speech Recognition State
    var isVoiceListening by remember { mutableStateOf(false) }

    // Omnibar State
    var omniText by rememberSaveable { mutableStateOf("") }
    var omniStatus by remember { mutableStateOf<String?>(null) }
    var isOmniLoading by remember { mutableStateOf(false) }

    // PC Remote State
    var pcVitals by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var isRefreshingVitals by remember { mutableStateOf(false) }
    var terminalCommand by rememberSaveable { mutableStateOf("") }
    var terminalOutput by rememberSaveable { mutableStateOf("Console ready. Enter a PowerShell command to execute on Friday PC.") }
    var isTerminalRunning by remember { mutableStateOf(false) }

    // PC File Explorer State
    var currentPcPath by rememberSaveable { mutableStateOf("C:\\") }
    var parentPcPath by rememberSaveable { mutableStateOf("") }
    var pcFilesList by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var isBrowsingFiles by remember { mutableStateOf(false) }

    // Mobile Autopilot State
    var autoClickText by rememberSaveable { mutableStateOf("") }
    var autoTypeText by rememberSaveable { mutableStateOf("") }
    var autopilotStatus by remember { mutableStateOf<String?>(null) }
    var screenDumpText by remember { mutableStateOf<List<String>>(emptyList()) }
    var isReadingScreen by remember { mutableStateOf(false) }
    var isFlashlightOn by rememberSaveable { mutableStateOf(false) }

    val accessibilityActive = FridayAccessibilityService.instance != null

    // AI Motion & Touch Gesture State
    var shakeToSpeakEnabled by rememberSaveable { mutableStateOf(true) }
    var flipToMuteEnabled by rememberSaveable { mutableStateOf(true) }
    var lastGestureLabel by remember { mutableStateOf("Swipe or Tap on Gesture Pad to control PC") }
    var padDragTotalX by remember { mutableStateOf(0f) }
    var padDragTotalY by remember { mutableStateOf(0f) }

    fun executeOmni(cmd: String) {
        if (cmd.isBlank()) return
        isOmniLoading = true
        omniStatus = "Sending command: $cmd..."
        remoteClient.sendRemoteCommand(cmd) { ok, resp ->
            mainHandler.post {
                isOmniLoading = false
                omniStatus = if (ok) resp else "Error: $resp"
            }
        }
    }

    // Speech Result Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVoiceListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                omniText = spoken
                executeOmni(spoken)
                Toast.makeText(context, "Command: \"$spoken\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Audio Permission Launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Friday is listening... Speak your command")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                isVoiceListening = true
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                isVoiceListening = false
                Toast.makeText(context, "Voice error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission required for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Friday is listening... Speak your command")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                isVoiceListening = true
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                isVoiceListening = false
                Toast.makeText(context, "Voice error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(shakeToSpeakEnabled, flipToMuteEnabled) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastShakeTime = 0L
        var lastFlipTime = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val now = System.currentTimeMillis()
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // ⚡ Shake Detection
                if (shakeToSpeakEnabled && now - lastShakeTime > 1800L) {
                    val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
                    if (gForce > 2.3f) {
                        lastShakeTime = now
                        mainHandler.post {
                            lastGestureLabel = "⚡ Shaken! Activating Friday Voice..."
                            Toast.makeText(context, "⚡ Shaken: Listening to command!", Toast.LENGTH_SHORT).show()
                            startListening()
                        }
                    }
                }

                // 🔇 Flip Face-Down Detection (Z-axis negative, phone face down)
                if (flipToMuteEnabled && now - lastFlipTime > 2200L) {
                    if (z < -8.4f && abs(x) < 4.2f && abs(y) < 4.2f) {
                        lastFlipTime = now
                        mainHandler.post {
                            lastGestureLabel = "🔇 Flipped Face-Down: PC Audio Muted"
                            Toast.makeText(context, "🔇 Face-Down: Muting PC", Toast.LENGTH_SHORT).show()
                            remoteClient.controlMedia("mute") { ok, msg ->
                                mainHandler.post { omniStatus = if (ok) "PC Muted via Flip Gesture" else msg }
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (accelerometer != null && (shakeToSpeakEnabled || flipToMuteEnabled)) {
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // Refresh vitals on launch
    LaunchedEffect(Unit) {
        remoteClient.fetchVitals { vitals ->
            mainHandler.post { pcVitals = vitals }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .verticalScroll(rememberScrollState()),
    ) {
        // --- SUPER APP HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "FRIDAY SUPER APP",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = androidx.compose.ui.graphics.Color.White
                )
                Text(
                    "Universal Control • Gaurav Edition",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Text(
                    "● Online",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- GLOWING FRIDAY ARC REACTOR VOICE ORB ---
        FridayVoiceReactorOrb(
            isListening = isVoiceListening,
            onOrbClick = { startListening() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(10.dp))

        // --- UNIVERSAL OMNIBAR ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                contentColor = androidx.compose.ui.graphics.Color.White
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = omniText,
                        onValueChange = { omniText = it },
                        placeholder = { Text("Ask Friday or speak your command...", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { startListening() },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (isVoiceListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
                        )
                    ) {
                        Text(if (isVoiceListening) "🔴" else "🎙️")
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val prompt = omniText.trim()
                            if (prompt.isNotBlank()) {
                                executeOmni(prompt)
                                omniText = ""
                            }
                        },
                        enabled = !isOmniLoading && omniText.isNotBlank()
                    ) {
                        Text(if (isOmniLoading) "..." else "Go")
                    }
                }

                omniStatus?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Quick Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { startListening() }
                    ) { Text("🎙️ Voice Command", style = MaterialTheme.typography.labelSmall) }

                    FilledTonalButton(
                        onClick = {
                            remoteClient.sendRemoteCommand("start listening") { ok, resp ->
                                mainHandler.post { omniStatus = if (ok) "PC listening..." else resp }
                            }
                        }
                    ) { Text("🗣️ PC Mic Listen", style = MaterialTheme.typography.labelSmall) }

                    FilledTonalButton(
                        onClick = {
                            remoteClient.downloadScreenshot()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download PC Screenshot")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // In-App Auto-Typer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("In-App Auto-Typer", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = autoTypeText,
                        onValueChange = { autoTypeText = it },
                        label = { Text("Text to type into active field") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val text = autoTypeText
                            val typed = FridayAccessibilityService.instance?.typeTextIntoField("", text) ?: false
                            autopilotStatus = if (typed) "Typed text into field" else "No active input field found on screen"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Type into Active Field")
                    }
                }
            }

            autopilotStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(12.dp))

            // System Gestures
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("System Navigation & Gestures", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.pressBack() }, modifier = Modifier.weight(1f)) {
                            Text("◀ Back", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.pressHome() }, modifier = Modifier.weight(1f)) {
                            Text("⌂ Home", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.pressRecents() }, modifier = Modifier.weight(1f)) {
                            Text("⧉ Recents", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.openNotifications() }, modifier = Modifier.weight(1f)) {
                            Text("🔔 Alerts", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.scroll("up") }, modifier = Modifier.weight(1f)) {
                            Text("⬆ Up", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick = { FridayAccessibilityService.instance?.scroll("down") }, modifier = Modifier.weight(1f)) {
                            Text("⬇ Down", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Screen Text Reader
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("On-Screen Text Scanner (UI Inspection)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val texts = FridayAccessibilityService.instance?.readScreenText() ?: emptyList()
                            screenDumpText = texts
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Capture & Read On-Screen Text")
                    }

                    if (screenDumpText.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFF334155))
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                screenDumpText.take(15).forEach { line ->
                                    Text("• $line", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- TAB 3: AI HUB & LOGS ---
        if (selectedTab == 3) {
            // Friday Chat Hero Card
            Card(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Open Friday AI Chat", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Contextual AI synced with your PC brain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                    }
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Send,
                        contentDescription = "Open Chat",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Device Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(gateway?.name ?: "Friday PC", fontWeight = FontWeight.Bold)
                    Text("Device: ${credential.deviceName}")
                    Text("Gateway: ${credential.gatewayHost}:${credential.gatewayPort}")
                    Text("State: $state")
                    Text("Status: $status")
                }
            }

            Spacer(Modifier.height(14.dp))

            // Permissions
            OutlinedButton(
                onClick = onOpenPermissions,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text("System Permissions & Diagnostics", color = androidx.compose.ui.graphics.Color.White)
            }

            Spacer(Modifier.height(10.dp))

            // Disconnect
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF991B1B)
                )
            ) {
                Text("Disconnect from Friday")
            }

            Spacer(Modifier.height(16.dp))

            // Sync Logs
            Text("Recent Synchronization Logs", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            logs.takeLast(10).forEach { line ->
                Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectedScreen(
    credential: DeviceCredential,
    gateway: GatewayEndpoint?,
    state: ConnectionState,
    logs: List<String>,
    status: String,
    onDisconnect: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAutopilotSettings: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val remote = remember { RemotePcClient(context) { credential } }
    var page by rememberSaveable { mutableStateOf("Control") }
    var command by rememberSaveable { mutableStateOf("") }
    var feedback by rememberSaveable { mutableStateOf("Friday is using your paired PC memory.") }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var parentPath by rememberSaveable { mutableStateOf("") }
    var files by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loadingFiles by remember { mutableStateOf(false) }
    var pcVitals by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var uploadPath by rememberSaveable { mutableStateOf("") }
    var autopilotEnabled by remember { mutableStateOf(FridayAccessibilityService.isEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autopilotEnabled = FridayAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun browse(path: String = currentPath) {
        loadingFiles = true
        remote.browseFiles(path) { ok, current, parent, entries -> mainHandler.post {
            loadingFiles = false
            if (ok) { currentPath = current; parentPath = parent; files = entries; feedback = "PC files ready" }
            else feedback = "Could not open this PC folder"
        }}
    }
    fun run(text: String) {
        if (text.isBlank()) return
        feedback = "Sending to your PC Friday…"
        remote.sendRemoteCommand(text) { ok, result -> mainHandler.post { feedback = if (ok) result else "PC unavailable: $result" } }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) remote.uploadFileToPc(uri, uploadPath) { ok, message -> mainHandler.post { feedback = if (ok) message else "Upload failed: $message" } }
    }
    LaunchedEffect(Unit) { remote.fetchVitals { result -> mainHandler.post { pcVitals = result } } }
    LaunchedEffect(page) { if (page == "Files" && files.isEmpty()) browse("") }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Friday", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("PC + phone workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .18f), border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = .16f))) {
                Text("Paired", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .08f), border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = .14f))) {
            Column(Modifier.padding(16.dp)) {
                Text("Shared Friday memory", fontWeight = FontWeight.SemiBold)
                Text("Messages from this phone are handled by Friday on ${gateway?.name ?: "your PC"}, so your project context stays consistent.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) { Text("Open Friday chat") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Control", "Files", "Phone").forEach { label ->
                if (page == label) Button(onClick = { page = label }, modifier = Modifier.weight(1f)) { Text(label) }
                else OutlinedButton(onClick = { page = label }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }

        when (page) {
            "Control" -> {
                GlassCard {
                    Text("Ask Friday to work on your PC", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Open VS Code, summarize this project…") }, maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { run(command); command = "" }, enabled = command.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send to PC Friday") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { remote.downloadScreenshot(); feedback = "PC screenshot is downloading" }, modifier = Modifier.weight(1f)) { Text("Screenshot") }
                    FilledTonalButton(onClick = { remote.controlMedia("mute") { ok, msg -> mainHandler.post { feedback = if (ok) "PC muted" else msg } }; }, modifier = Modifier.weight(1f)) { Text("Mute PC") }
                }
                FilledTonalButton(onClick = { remote.fetchVitals { result -> mainHandler.post { pcVitals = result; feedback = if (result == null) "PC status unavailable" else "PC status refreshed" } } }, modifier = Modifier.fillMaxWidth()) { Text("Refresh PC status") }
                pcVitals?.let { values ->
                    GlassCard {
                        Text("Your PC", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                        Text("${values["hostname"] ?: "PC"} · CPU ${values["cpu_percent"] ?: "–"}% · RAM ${values["ram_percent"] ?: "–"}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        val active = values["active_window"]?.toString().orEmpty()
                        if (active.isNotBlank()) Text(active, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            "Files" -> {
                GlassCard {
                    Text("PC files", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Text(if (currentPath.isBlank()) "Choose a drive or a quick folder" else currentPath, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (parentPath.isNotBlank()) browse(parentPath) else browse("") }, modifier = Modifier.weight(1f)) { Text("Back") }
                        OutlinedButton(onClick = { browse(currentPath) }, modifier = Modifier.weight(1f)) { Text(if (loadingFiles) "Loading" else "Refresh") }
                    }
                    Spacer(Modifier.height(8.dp))
                    files.take(80).forEach { item ->
                        val name = item["name"]?.toString().orEmpty()
                        val path = item["path"]?.toString().orEmpty()
                        val isDirectory = item["is_dir"] as? Boolean ?: false
                        OutlinedButton(onClick = { if (isDirectory) browse(path) else { remote.downloadFileOrFolder(path, name); feedback = "Downloading $name" } }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text((if (isDirectory) "Folder  " else "File  ") + name, maxLines = 1)
                        }
                    }
                }
                GlassCard {
                    Text("Send a file to PC", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    OutlinedTextField(value = uploadPath, onValueChange = { uploadPath = it }, modifier = Modifier.fillMaxWidth(), label = { Text("PC folder (optional)") }, placeholder = { Text("Defaults to Downloads/FromMobile") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose file from phone") }
                }
            }
            else -> {
                GlassCard {
                    Text("Phone Autopilot", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                    Text(
                        if (autopilotEnabled) "Enabled — Friday can use supported on-screen actions when your PC sends a paired command."
                        else "Disabled — enable Android Accessibility to allow supported phone actions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (autopilotEnabled) androidx.compose.ui.graphics.Color(0x2230D158) else MaterialTheme.colorScheme.primary.copy(alpha = .12f),
                    ) {
                        Text(
                            if (autopilotEnabled) "● Autopilot enabled" else "○ Autopilot needs setup",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (autopilotEnabled) androidx.compose.ui.graphics.Color(0xFF30D158) else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenAutopilotSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(if (autopilotEnabled) "Review Android Accessibility" else "Enable Autopilot")
                    }
                    if (!autopilotEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("If Android shows Restricted setting, open Friday Connect app info, tap ⋮, choose Allow restricted settings, then return here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) { Text("Open Friday Connect app info") }
                    }
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect this phone") }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .06f)) { Text(feedback, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Connection: $state · $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (logs.isNotEmpty()) Text(logs.last(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ManualConnectDialog(
    host: String,
    port: String,
    pairingCode: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPairingCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Manual PC Connection", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Enter your PC IP or Cloudflare tunnel URL and the 6-digit code shown on the desktop screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text("PC IP or Tunnel URL") },
                    placeholder = { Text("e.g. 192.168.1.13") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { if (it.length <= 6) onPairingCodeChange(it) },
                        label = { Text("6-Digit Code") },
                        placeholder = { Text("123456") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConnect,
                enabled = host.isNotBlank()
            ) {
                Text("Pair & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
private fun QrScannerScreen(
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    errorText: String?,
    onManualEntry: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewReady by remember { mutableStateOf(false) }
    var scanHandled by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(errorText) {
        if (!errorText.isNullOrBlank()) {
            delay(1800)
            scanHandled = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Scan PC QR Code", fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth().height(380.dp), shape = RoundedCornerShape(20.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PreviewView(viewContext).also { previewView ->
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build()
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            val scanner = BarcodeScanning.getClient(
                                BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build(),
                            )
                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                    scanner.process(image)
                                        .addOnSuccessListener { codes ->
                                            if (!scanHandled) {
                                                codes.firstOrNull()?.rawValue?.let { raw ->
                                                    scanHandled = true
                                                    onScanned(raw)
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis,
                            )
                            preview.setSurfaceProvider(previewView.surfaceProvider)
                            previewReady = true
                        }, ContextCompat.getMainExecutor(viewContext))
                    }
                }
            )
        }
        if (!previewReady) {
            Spacer(Modifier.height(10.dp))
            Text("Camera is preparing...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!errorText.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onManualEntry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enter 6-Digit Code Manually")
        }
    }
}
