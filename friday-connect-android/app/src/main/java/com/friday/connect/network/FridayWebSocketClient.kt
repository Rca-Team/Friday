package com.friday.connect.network

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.friday.connect.commands.DeviceCommandHandler
import com.friday.connect.core.AgentStateStore
import com.friday.connect.core.FridayProtocol
import com.friday.connect.core.FridayConnectCapabilities
import com.friday.connect.core.ChatMessage
import com.friday.connect.core.ConnectionState
import com.friday.connect.core.DeviceCredential
import com.friday.connect.core.DeviceSnapshot
import com.friday.connect.core.GatewayEndpoint
import com.friday.connect.core.PairingOffer
import com.friday.connect.pairing.PairingStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import com.friday.connect.assistant.GatewaySecurity
import com.friday.connect.assistant.AssistantEngine
import kotlinx.coroutines.*

class FridayWebSocketClient(
    private val context: Context,
    private val storage: PairingStorage,
    private val commandHandler: DeviceCommandHandler,
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        // A minute is responsive for a companion app while halving idle radio wakeups.
        .pingInterval(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private var currentEndpoint: GatewayEndpoint? = null
    private var currentOffer: PairingOffer? = null
    private var currentCredential: DeviceCredential? = null
    @Volatile private var manualDisconnect = false
    private var reconnectAttempt = 0
    private var lastConnectUptime = 0L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectScheduled = false
    private val pendingChatPayloads = mutableListOf<JSONObject>()
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val commandResults = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val executing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val journal = com.friday.connect.assistant.RemoteCommandJournal(context)
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        val endpoint = currentEndpoint ?: return@Runnable
        if (!manualDisconnect) connect(endpoint, currentCredential, currentOffer)
    }

    fun connect(endpoint: GatewayEndpoint, credential: DeviceCredential? = storage.loadCredential(), offer: PairingOffer? = null) {
        if (socket != null && currentEndpoint == endpoint) {
            currentCredential = credential ?: currentCredential
            currentOffer = offer ?: currentOffer
            AgentStateStore.setGateway(endpoint)
            return
        }
        currentEndpoint = endpoint
        reconnectScheduled = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        currentCredential = credential
        currentOffer = offer
        manualDisconnect = false
        lastConnectUptime = SystemClock.elapsedRealtime()
        AgentStateStore.setGateway(endpoint)
        AgentStateStore.setConnectionState(ConnectionState.CONNECTING)

        val tunnelUrl = endpoint.tunnelUrl 
            ?: currentCredential?.tunnelUrl 
            ?: currentOffer?.tunnelUrl

        val wsUrl = when {
            // A QR pairing payload includes the Cloudflare address when global
            // access is available. Always prefer it: the local LAN address in
            // the same payload cannot work on mobile data or another Wi-Fi.
            !tunnelUrl.isNullOrBlank() -> {
                val base = tunnelUrl.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/')
                AgentStateStore.setStatus("Connecting globally via tunnel...")
                if (base.endsWith("/ws")) base else "$base/ws"
            }
            endpoint.host.startsWith("ws://") || endpoint.host.startsWith("wss://") -> {
                val base = endpoint.host.trimEnd('/')
                AgentStateStore.setStatus("Connecting to ${endpoint.name}...")
                if (base.endsWith("/ws")) base else "$base/ws"
            }
            endpoint.host.startsWith("http://") || endpoint.host.startsWith("https://") -> {
                val base = endpoint.host.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/')
                AgentStateStore.setStatus("Connecting to ${endpoint.name}...")
                if (base.endsWith("/ws")) base else "$base/ws"
            }
            else -> {
                AgentStateStore.setStatus("Connecting to ${endpoint.name}...")
                "ws://${endpoint.host}:${endpoint.port}/ws"
            }
        }

        try {
            val check = DeviceCredential("", "", "", endpoint.host, endpoint.port, tunnelUrl = tunnelUrl)
            GatewaySecurity.base(check)
        } catch (e: Exception) {
            AgentStateStore.setError(e.message)
            AgentStateStore.setConnectionState(ConnectionState.DISCONNECTED)
            return
        }
        socket?.close(1000, "Reconnecting")
        socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            FridaySocketListener(),
        )
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectScheduled = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        val activeSocket = socket
        socket = null
        activeSocket?.close(1000, "Disconnected by user")
        // Do not wait for a close handshake when the app/service is stopping.
        // Cancelling also aborts DNS, tunnel and TLS work already in flight.
        activeSocket?.cancel()
        pendingChatPayloads.clear()
        AgentStateStore.setConnectionState(ConnectionState.DISCONNECTED)
        AgentStateStore.setStatus("Disconnected")
    }

    fun shutdown() {
        disconnect()
        commandScope.cancel()
        journal.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun reconnectLater() {
        if (manualDisconnect || currentEndpoint == null || reconnectScheduled) return
        reconnectAttempt += 1
        AgentStateStore.setConnectionState(ConnectionState.RECONNECTING)
        // Do not keep waking a mobile radio when the PC or tunnel is offline.
        // The first retries recover short Wi-Fi switches quickly; later ones back
        // off to five minutes until the user sends a new command or opens Friday.
        val delayMs = min(300_000L, 2_000L * (1 shl min(reconnectAttempt, 8)))
        AgentStateStore.setStatus("Reconnecting in ${delayMs / 1000}s")
        reconnectScheduled = true
        reconnectHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun flushPendingChat() {
        if (pendingChatPayloads.isEmpty()) return
        val queued = pendingChatPayloads.toList()
        pendingChatPayloads.clear()
        queued.forEach { send(it) }
    }

    private fun batterySnapshot(): Pair<Int, Boolean> {
        val battery = commandHandler.handle("get_battery", emptyMap()).data
        val percentage = (battery["percentage"] as? Int) ?: -1
        val charging = (battery["charging"] as? Boolean) ?: false
        return percentage to charging
    }

    private fun buildSnapshot(): DeviceSnapshot {
        val credential = currentCredential
        val (percentage, charging) = batterySnapshot()
        return DeviceSnapshot(
            deviceId = credential?.deviceId,
            deviceName = credential?.deviceName ?: android.os.Build.MODEL ?: "Android",
            androidVersion = android.os.Build.VERSION.RELEASE ?: "Unknown",
            agentVersion = "1.0.0",
            batteryPercentage = percentage,
            charging = charging,
            wifiEnabled = true,
            capabilities = FridayConnectCapabilities.INITIAL,
        )
    }

    private fun sendHello() {
        send(FridayProtocol.hello(buildSnapshot()))
        AgentStateStore.setStatus("Awaiting approval")
    }

    private fun sendPairRequest() {
        val offer = currentOffer ?: run {
            sendHello()
            return
        }
        val snapshot = buildSnapshot().toJson()
            .put("pairing_token", offer.pairingToken)
            .put("pairing_code", offer.pairingCode)
        send(FridayProtocol.envelope(FridayProtocol.PAIR_REQUEST, snapshot))
        AgentStateStore.setStatus("Pairing request sent")
    }

    private fun sendAuthenticate() {
        val credential = currentCredential ?: storage.loadCredential()
        if (credential == null) {
            sendHello()
            return
        }
        currentCredential = credential
        send(FridayProtocol.authenticate(credential))
        AgentStateStore.setStatus("Authenticating")
    }

    private fun handleCommandMessage(root: JSONObject) {
        val requestId = root.optString("request_id")
        if (AgentStateStore.connectionState.value != ConnectionState.CONNECTED || currentCredential == null || requestId.isBlank()) return
        commandResults[requestId]?.let { send(it); return }
        if (!executing.add(requestId)) return
        val payload = root.optJSONObject("payload") ?: JSONObject()
        val action = payload.optString("action")
        val params = payload.optJSONObject("parameters") ?: JSONObject()
        val parameterMap = buildMap<String, Any?> {
            params.keys().forEach { key ->
                put(key, nativeJson(params.opt(key)))
            }
        }
        commandScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { journal.claim(currentCredential!!.deviceId, requestId) }) {
                    send(FridayProtocol.envelope(FridayProtocol.RESULT, com.friday.connect.core.CommandResult(false, error = "Command already received; outcome may be unknown. No replay.").toJson(), requestId))
                    return@launch
                }
                val passive = action in setOf("get_battery", "get_device_info", "volume_get", "list_installed_apps")
                val needsTarget = action.startsWith("ui_") || action in setOf("read_screen_text", "run_workflow")
                val target = if (needsTarget) com.friday.connect.assistant.AccessibilityTarget.capture(context) else null
                val approved = passive || AssistantEngine.get(context).confirm("PC requests ${action.replace('_', ' ')}", params.toString(2) + (target?.let { "\nTarget app: $it" } ?: ""))
                val restored = !needsTarget || (target != null && com.friday.connect.assistant.AccessibilityTarget.restore(context, target))
                val result = if (approved && restored) withContext(Dispatchers.IO) { commandHandler.handle(action, parameterMap) }
                    else com.friday.connect.core.CommandResult(false, error = "User declined or approval expired. Do not retry.")
                val response = FridayProtocol.envelope(FridayProtocol.RESULT, result.toJson(), requestId)
                if (commandResults.size >= 128) commandResults.clear()
                commandResults[requestId] = response
                send(response)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { send(FridayProtocol.envelope(FridayProtocol.RESULT, com.friday.connect.core.CommandResult(false, error = "Phone action interrupted or failed. Check the phone before retrying.").toJson(), requestId)) }
            finally { executing.remove(requestId) }
        }
    }

    private fun handlePairApproved(root: JSONObject) {
        val payload = root.optJSONObject("payload") ?: JSONObject()
        val device = payload.optJSONObject("device") ?: JSONObject()
        val secret = payload.optString("device_secret")
        val deviceId = device.optString("device_id")
        val deviceName = device.optString("name", android.os.Build.MODEL ?: "Android")
        val tunnelUrl = payload.optString("tunnel_url").takeIf { it.isNotBlank() && it != "null" } 
            ?: currentOffer?.tunnelUrl 
            ?: currentEndpoint?.tunnelUrl
        val credential = DeviceCredential(
            deviceId = deviceId,
            deviceSecret = secret,
            deviceName = deviceName,
            gatewayHost = currentEndpoint?.host.orEmpty(),
            gatewayPort = currentEndpoint?.port ?: 8765,
            tunnelUrl = tunnelUrl,
        )
        storage.saveCredential(credential)
        storage.clearGatewayHint()
        currentCredential = credential
        AgentStateStore.setCredential(credential)
        AgentStateStore.setPairingOffer(null)
        AgentStateStore.setStatus("Pair approved")
        sendAuthenticate()
    }

    private inner class FridaySocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
            reconnectScheduled = false
            reconnectHandler.removeCallbacks(reconnectRunnable)
            reconnectAttempt = 0
            AgentStateStore.setConnectionState(ConnectionState.CONNECTING)
            if (currentCredential != null || storage.loadCredential() != null) {
                sendAuthenticate()
            } else if (currentOffer != null) {
                sendPairRequest()
            } else {
                sendHello()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val root = JSONObject(text)
                val type = root.optString("type")
                when (type) {
                    FridayProtocol.PAIR_REQUEST -> {
                        AgentStateStore.setStatus("Waiting for approval")
                    }
                    FridayProtocol.PAIR_APPROVED -> handlePairApproved(root)
                    FridayProtocol.DEVICE_ONLINE -> {
                        AgentStateStore.setConnectionState(ConnectionState.CONNECTED)
                        AgentStateStore.setStatus("Connected")
                        flushPendingChat()
                    }
                    FridayProtocol.CAPABILITIES -> {
                        AgentStateStore.addLog("Capabilities synced")
                    }
                    FridayProtocol.EXECUTE -> handleCommandMessage(root)
                    FridayProtocol.PING -> {
                        send(FridayProtocol.envelope(FridayProtocol.PONG, JSONObject().put("status", "ok"), requestId = root.optString("request_id")))
                    }
                    FridayProtocol.ERROR -> {
                        AgentStateStore.setError(root.optJSONObject("payload")?.optString("error"))
                    }
                    FridayProtocol.CHAT_MESSAGE -> {
                        val payload = root.optJSONObject("payload") ?: return
                        val role = payload.optString("role", "system")
                        val text = payload.optString("text", "")
                        val msgId = root.optString("request_id")
                        val timestamp = System.currentTimeMillis()
                        val msg = ChatMessage("$msgId:$role", role, text, timestamp, "Sent")
                        AgentStateStore.addChatMessage(msg)
                    }
                }
            }.onFailure {
                AgentStateStore.setError(it.message)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // A replaced socket may finish closing after its successor opens.
            // Ignore that stale callback so it cannot tear down the live socket.
            if (socket !== webSocket) return
            socket = null
            AgentStateStore.addLog("Socket closed: $code $reason")
            AgentStateStore.setConnectionState(ConnectionState.DISCONNECTED)
            AgentStateStore.setStatus("Disconnected")
            reconnectLater()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (socket !== webSocket) return
            socket = null
            AgentStateStore.addLog("Socket failure: ${t.message}")
            AgentStateStore.setConnectionState(ConnectionState.DISCONNECTED)
            AgentStateStore.setStatus("Connection failed")
            reconnectLater()
        }
    }

    fun sendChatMessage(text: String) {
        if (AgentStateStore.connectionState.value != ConnectionState.CONNECTED || socket == null) {
            AgentStateStore.setError("PC is offline. Commands are not queued; reconnect and send manually.")
            return
        }
        val payload = FridayProtocol.chatMessage(text)
        val msgId = payload.getString("request_id")
        val timestamp = System.currentTimeMillis()
        val pending = ChatMessage(msgId, "user", text, timestamp, "Sending...")
        AgentStateStore.addChatMessage(pending)
        if (AgentStateStore.connectionState.value == ConnectionState.CONNECTED && socket != null) {
            send(payload)
        }
        val sent = pending.copy(status = "Sent")
        AgentStateStore.addChatMessage(sent)
    }

    private fun connectIfCurrentEndpoint() {
        val endpoint = currentEndpoint ?: return
        if (socket == null) connect(endpoint, currentCredential, currentOffer)
    }
    private fun nativeJson(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { nativeJson(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { nativeJson(value.opt(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
