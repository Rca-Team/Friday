package com.friday.connect.assistant

import com.friday.connect.core.DeviceCredential
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AssistantTests {
    @Test fun englishHindiWakeAndBoundaries() {
        listOf("Friday", "wake up Friday open camera", "utho Friday", "iutho firday", "उठो फ्राइडे", "फ्राईडे कैमरा खोलो").forEach { assertTrue(it, WakePolicy.hasWake(it)) }
        assertFalse(WakePolicy.hasWake("fridayish")); assertFalse(WakePolicy.hasWake("hello"))
        assertEquals("open camera", WakePolicy.command("wake up Friday open camera"))
        assertEquals("कैमरा खोलो", WakePolicy.command("उठो फ्राइडे कैमरा खोलो"))
        assertTrue(WakePolicy.isStop("रुको"))
    }
    @Test fun remoteTransportRequiresTlsAndPrivateLanException() {
        fun credential(host: String, tunnel: String? = null) = DeviceCredential("d", "s", "Phone", host, 8765, tunnelUrl = tunnel)
        assertEquals("http://192.168.1.20:8765", GatewaySecurity.base(credential("192.168.1.20")))
        assertEquals("https://pc.example.com", GatewaySecurity.base(credential("unused", "https://pc.example.com")))
        listOf("http://pc.example.com", "http://8.8.8.8", "https://user:password@pc.example.com").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { GatewaySecurity.base(credential("unused", url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { GatewaySecurity.base(credential("172.32.0.1")) }
    }
    @Test fun streamingPreservesFunctionSignaturesAndUsesHeaderKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n\ndata: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_battery\",\"args\":{}},\"thoughtSignature\":\"signature\"}]}}]}\n\n"))
            val chunks = mutableListOf<String>()
            val p = CloudProvider("test-key", "test-model", apiBase = server.url("/").toString().trimEnd('/'))
            val result = p.stream(JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Hello")))), "Friday", JSONArray()) { chunks.add(it) }
            assertEquals("Hello", result.text); assertEquals(listOf("Hello"), chunks); assertEquals(1, result.calls.size)
            assertEquals("signature", result.parts.getJSONObject(1).getString("thoughtSignature"))
            val request = server.takeRequest(); assertEquals("test-key", request.getHeader("x-goog-api-key")); assertFalse(request.path!!.contains("test-key"))
        }
    }
    @Test fun invalidKeyDoesNotLeakResponseOrCredential() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret details test-key"))
            try { CloudProvider("test-key", "test-model", apiBase = server.url("/").toString().trimEnd('/')).stream(JSONArray(), "Friday", JSONArray()) {}; fail("Expected rejection") }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("API key rejected")); assertFalse(e.message!!.contains("test-key")); assertFalse(e.message!!.contains("secret details")) }
        }
    }
    @Test fun cancellingStopsNetworkRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val job = launch { CloudProvider("test-key", "test-model", apiBase = server.url("/").toString().trimEnd('/')).stream(JSONArray(), "Friday", JSONArray()) {} }
            delay(200); withTimeout(2000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
        }
    }
    @Test fun dangerousToolsRequireConfirmationAndBoundedWorkflow() {
        listOf("compose_sms", "pc_task", "ui_type", "ui_click_text", "read_screen_text", "read_notifications", "save_memory", "cancel_reminder").forEach { name ->
            assertTrue(name, PhoneToolCatalog.definitions.first { it.name == name }.confirm)
        }
        assertFalse(PhoneToolCatalog.definitions.any { it.name == "unlock_phone" || it.name == "run_workflow" })
    }
    @Test fun openRouterStreamAndToolArguments() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"volume_set\",\"arguments\":\"{\\\"percentage\\\":\"}}]}}]}\n\ndata: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"30}\"}}]}}]}\n\ndata: [DONE]\n\n"))
            val result = CloudProvider("router-key", "test-model", true, apiBase = server.url("/").toString().trimEnd('/')).stream(JSONArray(), "Friday", JSONArray()) {}
            assertEquals("volume_set", result.calls.first().getString("name")); assertEquals(30, result.calls.first().getJSONObject("args").getInt("percentage"))
        }
    }
}
