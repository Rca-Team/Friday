package com.friday.connect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.friday.connect.core.AgentStateStore
import com.friday.connect.pairing.PairingStorage
import com.friday.connect.ui.FridayAssistantApp
import com.friday.connect.ui.theme.FridayConnectTheme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var storage: PairingStorage
    private var pendingServiceStart = false

    private var cameraGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        updatePermissionStates()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        updatePermissionStates()
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (pendingServiceStart) {
                pendingServiceStart = false
                startGatewayService()
            }
        } else {
            pendingServiceStart = false
        }
    }

    private fun updatePermissionStates() {
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = PairingStorage(this)
        AgentStateStore.setCredential(storage.loadCredential())
        val hint = storage.loadGatewayHint()
        if (hint != null && !hint.isExpired() && storage.loadCredential() == null) {
            AgentStateStore.setPairingOffer(hint)
            AgentStateStore.setGateway(
                com.friday.connect.core.GatewayEndpoint(
                    name = "Friday PC",
                    host = hint.host,
                    port = hint.port,
                    tunnelUrl = hint.tunnelUrl,
                )
            )
        } else if (hint != null && hint.isExpired()) {
            storage.clearGatewayHint()
        }
        updatePermissionStates()
        maybeStartService()
        setContent {
            FridayConnectTheme {
                FridayAssistantApp(
                    cameraGranted = cameraGranted,
                    notificationsGranted = notificationsGranted,
                    onRequestCameraPermission = {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    onRequestNotificationPermission = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onStartService = { maybeStartService() },
                )
            }
        }
    }

    override fun onDestroy() {
        // Back/close should stop the companion connection. Configuration
        // changes such as rotation recreate the activity and must not stop it.
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, FridayConnectForegroundService::class.java))
        }
        super.onDestroy()
    }

    private fun maybeStartService() {
        val endpoint = AgentStateStore.gateway.value
        if (endpoint != null || AgentStateStore.credential.value != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingServiceStart = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            startGatewayService()
        }
    }

    private fun startGatewayService() {
        val endpoint = AgentStateStore.gateway.value
        if (endpoint != null || AgentStateStore.credential.value != null) {
            val intent = Intent(this, FridayConnectForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
