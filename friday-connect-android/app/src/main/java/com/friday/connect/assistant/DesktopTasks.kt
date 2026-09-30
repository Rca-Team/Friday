package com.friday.connect.assistant

import com.friday.connect.core.DeviceCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GatewaySecurity {
    fun base(credential: DeviceCredential): String {
        val raw = credential.tunnelUrl?.takeIf { it.isNotBlank() } ?: credential.gatewayHost.let {
            if (it.contains("://")) it else "http://$it:${credential.gatewayPort}"
        }
        val url = raw.replace("wss://", "https://").replace("ws://", "http://").toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null) { "Invalid gateway URL" }
        val h = url.host.lowercase()
        val octets = h.split('.').mapNotNull { it.toIntOrNull() }
        val lan = h == "localhost" || h == "::1" || (octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31))
        require(url.isHttps || (credential.tunnelUrl.isNullOrBlank() && lan)) { "Remote connections require HTTPS/WSS. Pair using the PC's secure tunnel QR code." }
        return url.toString().trimEnd('/').removeSuffix("/ws")
    }
}
class DesktopTasks(private val credential: DeviceCredential) {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    suspend fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject {
        val r = Request.Builder().url(GatewaySecurity.base(credential) + path).header("X-Friday-Device", credential.deviceId)
            .header("Authorization", "Bearer ${credential.deviceSecret}")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { c -> val call = client.newCall(r); c.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(IOException("PC connection lost. Task outcome may be unknown; check the PC before retrying.")) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { if (!it.isSuccessful) throw IOException("PC rejected request (HTTP ${it.code}). Pair again or check PC availability.")
                        val json = JSONObject(it.body?.string() ?: "{}"); if (c.isActive) c.resume(json) } }
                    catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
                }
            })
        }
    }
}
