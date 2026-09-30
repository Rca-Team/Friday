package com.friday.connect.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class Attachment(val name: String, val mime: String, val path: String) {
    fun part(): JSONObject {
        val bytes = File(path).readBytes()
        return if (mime.startsWith("text/") || mime == "application/json")
            JSONObject().put("text", "Attached file $name:\n" + bytes.toString(Charsets.UTF_8))
        else JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }
    fun json() = JSONObject().put("name", name).put("mime", mime).put("path", path)
}
data class AssistantMessage(val id: String = UUID.randomUUID().toString(), val role: String, val text: String,
    val status: String = "Complete", val attachments: List<Attachment> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("role", role).put("text", text).put("status", status)
        .put("attachments", JSONArray(attachments.map { it.json() }))
}
data class Conversation(val id: String = UUID.randomUUID().toString(), val title: String = "New conversation",
    val messages: List<AssistantMessage> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("title", title).put("messages", JSONArray(messages.map { it.json() }))
}
interface ConversationRepository {
    val conversations: StateFlow<List<Conversation>>
    fun save(conversation: Conversation)
    fun delete(id: String)
}
class LocalConversations(private val context: Context) : ConversationRepository {
    private val file = File(context.filesDir, "assistant-conversations.json")
    private val state = MutableStateFlow(load())
    override val conversations: StateFlow<List<Conversation>> = state
    private fun load(): List<Conversation> = runCatching {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).map { i ->
            val c = array.getJSONObject(i); val messages = c.getJSONArray("messages")
            Conversation(c.getString("id"), c.getString("title"), (0 until messages.length()).map { j ->
                val m = messages.getJSONObject(j); val a = m.optJSONArray("attachments") ?: JSONArray()
                AssistantMessage(m.getString("id"), m.getString("role"), m.getString("text"),
                    m.optString("status").let { if (it == "Working") "Interrupted" else it },
                    (0 until a.length()).map { k -> val x = a.getJSONObject(k); Attachment(x.getString("name"), x.getString("mime"), x.getString("path")) })
            })
        }
    }.getOrDefault(emptyList())
    @Synchronized override fun save(conversation: Conversation) {
        val next = (listOf(conversation) + state.value.filterNot { it.id == conversation.id }).take(100)
        persist(next)
        state.value = next
    }
    @Synchronized override fun delete(id: String) {
        val removed = state.value.firstOrNull { it.id == id }
        val next = state.value.filterNot { it.id == id }
        persist(next); state.value = next
        val retained = state.value.flatMap { it.messages }.flatMap { it.attachments }.map { it.path }.toSet()
        removed?.messages?.flatMap { it.attachments }?.filterNot { it.path in retained }?.forEach { File(it.path).delete() }
    }
    private fun persist(snapshot: List<Conversation>) {
        val temporary = File(file.parentFile, file.name + ".new")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(JSONArray(snapshot.map { it.json() }).toString().toByteArray()); stream.fd.sync() }
            // Replace, rather than renameTo: replacement semantics must be
            // correct on both Android and the Windows-hosted test filesystem.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun importAttachment(uri: Uri): Attachment {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        require(mime.startsWith("image/") || mime.startsWith("text/") || mime in listOf("application/pdf", "application/json")) {
            "Choose an image, PDF, text, or JSON file."
        }
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "Attachment"
        } ?: "Attachment"
        val folder = File(context.filesDir, "attachments").apply { mkdirs() }
        val target = File(folder, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val read = input.read(buffer); if (read < 0) break; total += read
                    require(total <= 5 * 1024 * 1024) { "Attachment exceeds 5 MB. Choose a smaller file." }; output.write(buffer, 0, read) }
            } }
            return Attachment(name.take(160), mime, target.absolutePath)
        } catch (e: Exception) { target.delete(); throw e }
    }
}
