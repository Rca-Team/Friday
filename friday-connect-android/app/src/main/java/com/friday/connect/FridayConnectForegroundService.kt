package com.friday.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.friday.connect.commands.DeviceCommandHandler
import com.friday.connect.core.AgentStateStore
import com.friday.connect.core.ConnectionState
import com.friday.connect.network.FridayWebSocketClient
import com.friday.connect.pairing.PairingStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel

class FridayConnectForegroundService : Service() {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var storage: PairingStorage
    private lateinit var client: FridayWebSocketClient
    private var started = false

    override fun onCreate() {
        super.onCreate()
        storage = PairingStorage(this)
        client = FridayWebSocketClient(this, storage, DeviceCommandHandler(this))
        createNotificationChannel()
        try {
            val notification = buildNotification("Friday Connect", "Starting connection")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (security: SecurityException) {
            stopSelf()
            return
        } catch (t: Throwable) {
            stopSelf()
            return
        }
        scope.launch {
            AgentStateStore.connectionState.collect {
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopConnection()
                return START_NOT_STICKY
            }
            ACTION_SEND_CHAT -> {
                val text = intent.getStringExtra(EXTRA_CHAT_TEXT)
                connectIfPossible()
                if (!text.isNullOrBlank()) {
                    client.sendChatMessage(text)
                }
                return START_NOT_STICKY
            }
        }
        connectIfPossible()
        // Friday is explicitly started by the paired app. Avoid an unnecessary
        // background restart loop after Android reclaims the idle service.
        return START_NOT_STICKY
    }

    private fun connectIfPossible() {
        val credential = storage.loadCredential()
        val endpoint = AgentStateStore.gateway.value ?: credential?.let {
            com.friday.connect.core.GatewayEndpoint(
                name = "Friday PC",
                host = it.gatewayHost,
                port = it.gatewayPort,
                tunnelUrl = it.tunnelUrl,
            )
        } ?: return
        val offer = AgentStateStore.pairingOffer.value ?: storage.loadGatewayHint()
        if (!started) {
            started = true
        }
        client.connect(endpoint, credential, offer)
        AgentStateStore.setConnectionState(ConnectionState.CONNECTING)
    }

    private fun updateNotification() {
        val state = AgentStateStore.connectionState.value
        val text = when (state) {
            ConnectionState.CONNECTED -> "Connected to Friday"
            ConnectionState.CONNECTING -> "Connecting to Friday"
            ConnectionState.RECONNECTING -> "Reconnecting"
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification("Friday Connect", text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, FridayConnectForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            4202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_friday_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Friday Connect", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 limits data-sync foreground-service time. Stop promptly
    // when the OS expires it instead of crashing or restarting in a loop.
    override fun onTimeout(startId: Int, fgsType: Int) {
        AgentStateStore.setError("Android paused the PC background connection. Open Friday and reconnect.")
        stopConnection()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping FRIDAY away means the user closed it. Stop all reconnect,
        // ping and tunnel traffic instead of leaving the foreground service alive.
        stopConnection()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopConnection() {
        if (::client.isInitialized) client.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.friday.connect.action.STOP"
        const val ACTION_SEND_CHAT = "com.friday.connect.action.SEND_CHAT"
        const val EXTRA_CHAT_TEXT = "com.friday.connect.extra.CHAT_TEXT"
        private const val CHANNEL_ID = "friday_connect"
        private const val NOTIFICATION_ID = 4201
    }
}
