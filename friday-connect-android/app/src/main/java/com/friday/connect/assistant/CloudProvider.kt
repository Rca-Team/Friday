package com.friday.connect.assistant

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ProviderReply(val parts: JSONArray, val text: String, val calls: List<JSONObject>)
interface AssistantProvider {
    suspend fun stream(contents: JSONArray, system: String, tools: JSONArray, onText: (String) -> Unit): ProviderReply
}
class CloudProvider(private val key: String, private val model: String, private val router: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build(),
    private val apiBase: String? = null) : AssistantProvider {
    override suspend fun stream(contents: JSONArray, system: String, tools: JSONArray, onText: (String) -> Unit): ProviderReply {
        require(key.isNotBlank()) { "Add your Gemini API key in Settings." }
        require(model.matches(Regex("[a-zA-Z0-9_./:-]+"))) { "Invalid model name." }
        val body = if (router) routerBody(contents, system, tools) else JSONObject().put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
        val url = if (router) (apiBase ?: "https://openrouter.ai") + "/api/v1/chat/completions"
            else (apiBase ?: "https://generativelanguage.googleapis.com") + "/v1beta/models/$model:streamGenerateContent?alt=sse"
        val request = Request.Builder().url(url).header(if (router) "Authorization" else "x-goog-api-key", if (router) "Bearer $key" else key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("AI connection failed. Check internet and retry manually.")) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { r ->
                        if (!r.isSuccessful) throw IOException(when (r.code) {
                            400 -> "AI request rejected. Check the selected model and attachments."
                            401, 403 -> "API key rejected. Check your key in Settings."
                            429 -> "AI quota reached. Wait or check your provider billing."
                            else -> "AI provider unavailable (HTTP ${r.code}). Retry manually."
                        })
                        val source = r.body?.source() ?: throw IOException("Empty AI response")
                        val parts = JSONArray(); val calls = mutableListOf<JSONObject>(); val output = StringBuilder()
                        val routerCalls = mutableMapOf<Int, Pair<StringBuilder, StringBuilder>>()
                        while (continuation.isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim(); if (data == "[DONE]") break
                            val root = JSONObject(data)
                            if (root.has("error")) throw IOException("AI stream failed. Retry manually.")
                            if (router) {
                                val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                                val text = delta.optString("content", "").takeUnless { it == "null" }.orEmpty()
                                if (text.isNotEmpty()) { output.append(text); onText(text) }
                                val tc = delta.optJSONArray("tool_calls") ?: JSONArray()
                                for (i in 0 until tc.length()) { val item = tc.getJSONObject(i); val f = item.optJSONObject("function") ?: continue
                                    val pair = routerCalls.getOrPut(item.optInt("index")) { StringBuilder() to StringBuilder() }
                                    pair.first.append(f.optString("name", "")); pair.second.append(f.optString("arguments", "")) }
                            } else {
                                val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                                val chunk = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                                for (i in 0 until chunk.length()) { val part = chunk.getJSONObject(i)
                                    // Preserve signatures and complete model parts for function-response round trips.
                                    parts.put(part)
                                    val text = if (part.optBoolean("thought")) "" else part.optString("text", "")
                                    if (text.isNotEmpty()) { output.append(text); onText(text) }
                                    part.optJSONObject("functionCall")?.let { calls.add(it) }
                                }
                                if (root.optJSONObject("promptFeedback")?.has("blockReason") == true || candidate?.optString("finishReason") in listOf("SAFETY", "RECITATION"))
                                    throw IOException("The provider could not answer this request.")
                            }
                        }
                        if (router) {
                            if (output.isNotEmpty()) parts.put(JSONObject().put("text", output.toString()))
                            routerCalls.toSortedMap().values.forEach { (name, args) ->
                                val fc = JSONObject().put("name", name.toString()).put("args", JSONObject(args.toString().ifBlank { "{}" }))
                                calls.add(fc); parts.put(JSONObject().put("functionCall", fc))
                            }
                        }
                        if (output.isEmpty() && calls.isEmpty()) throw IOException("The provider returned no answer. Try another model.")
                        if (continuation.isActive) continuation.resume(ProviderReply(parts, output.toString(), calls))
                    } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    private fun routerBody(contents: JSONArray, system: String, tools: JSONArray): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        var callIndex = 0
        for (i in 0 until contents.length()) {
            val c = contents.getJSONObject(i); val parts = c.getJSONArray("parts"); val text = StringBuilder(); val blocks = JSONArray(); val calls = JSONArray()
            for (j in 0 until parts.length()) {
                val p = parts.getJSONObject(j)
                p.optJSONObject("functionResponse")?.let { f ->
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call_${callIndex++}").put("content", f.getJSONObject("response").toString()))
                }
                p.optJSONObject("functionCall")?.let { f -> calls.put(JSONObject().put("id", "call_${callIndex + calls.length()}").put("type", "function")
                    .put("function", JSONObject().put("name", f.getString("name")).put("arguments", f.optJSONObject("args")?.toString() ?: "{}"))) }
                p.optJSONObject("inlineData")?.let { d ->
                    require(d.getString("mimeType").startsWith("image/")) { "PDF attachments require Gemini. OpenRouter fallback supports images and text." }
                    blocks.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${d.getString("mimeType")};base64,${d.getString("data")}")))
                }
                if (p.has("text")) text.append(p.getString("text"))
            }
            if (text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", text.toString()))
            if (blocks.length() > 0 || calls.length() > 0) messages.put(JSONObject().put("role", if (c.getString("role") == "model") "assistant" else "user")
                .put("content", if (blocks.length() == 0) JSONObject.NULL else blocks).apply { if (calls.length() > 0) put("tool_calls", calls) })
        }
        return JSONObject().put("model", model).put("stream", true).put("messages", messages)
            .put("tools", JSONArray((0 until tools.length()).map { JSONObject().put("type", "function").put("function", tools.getJSONObject(it)) }))
    }
}
