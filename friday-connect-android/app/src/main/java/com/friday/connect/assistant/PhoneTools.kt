package com.friday.connect.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.friday.connect.commands.DeviceCommandHandler
import com.friday.connect.core.CommandResult
import com.friday.connect.accessibility.FridayAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ToolDefinition(val name: String, val description: String, val target: String = "Android-native",
    val confirm: Boolean = false, val permissions: List<String> = emptyList(), val properties: Map<String, String> = emptyMap(),
    val required: List<String> = properties.keys.toList(), val cancellable: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("name", name).put("description", description + if (confirm) " Requires user confirmation." else "")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply {
            properties.forEach { (name, spec) -> put(name, JSONObject().put("type", spec.substringBefore(':')).put("description", spec.substringAfter(':'))) }
        }).put("required", JSONArray(required)))
}
interface ToolExecutor { suspend fun execute(name: String, args: JSONObject): CommandResult }
object PhoneToolCatalog {
    val definitions = listOf(
        ToolDefinition("recall_memory", "Read personal memory stored on this phone."),
        ToolDefinition("save_memory", "Replace phone personal memory with user-approved preferences.", confirm = true, properties = mapOf("text" to "string:Complete new phone memory; preserve existing facts unless the user asks to change them")),
        ToolDefinition("get_battery", "Read phone battery and charging status."),
        ToolDefinition("get_device_info", "Read phone diagnostics."),
        ToolDefinition("volume_get", "Read phone media volume."),
        ToolDefinition("volume_set", "Set phone media volume.", properties = mapOf("percentage" to "integer:Volume 0 to 100")),
        ToolDefinition("flashlight_on", "Turn phone flashlight on.", permissions = listOf(Manifest.permission.CAMERA)),
        ToolDefinition("flashlight_off", "Turn phone flashlight off.", permissions = listOf(Manifest.permission.CAMERA)),
        ToolDefinition("list_installed_apps", "List launchable phone applications."),
        ToolDefinition("launch_app", "Open a phone app by name or package.", properties = mapOf("app_name" to "string:App name")),
        ToolDefinition("open_url", "Open an HTTPS website in the phone browser.", properties = mapOf("url" to "string:HTTPS URL")),
        ToolDefinition("open_dialer", "Open phone dialer; the user completes the call.", confirm = true, properties = mapOf("number" to "string:Phone number")),
        ToolDefinition("compose_sms", "Prepare a message in the messaging app; the user sends it.", confirm = true,
            properties = mapOf("number" to "string:Recipient number", "message" to "string:Message text")),
        ToolDefinition("calendar_event", "Prepare a calendar event for user review.", confirm = true,
            properties = mapOf("title" to "string:Event title", "start_ms" to "integer:Start epoch milliseconds", "duration_minutes" to "integer:Duration minutes")),
        ToolDefinition("create_reminder", "Schedule a local notification reminder; timing is approximate.", permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            properties = mapOf("title" to "string:Reminder text", "minutes" to "integer:Minutes from now, 1 to 525600")),
        ToolDefinition("list_reminders", "List saved phone reminders."),
        ToolDefinition("cancel_reminder", "Cancel a phone reminder.", confirm = true, properties = mapOf("id" to "string:Reminder ID")),
        ToolDefinition("media_control", "Send a phone media play/pause/next/previous command.", properties = mapOf("action" to "string:play, pause, next, previous")),
        ToolDefinition("read_screen_text", "Read visible phone screen using accessibility, then send it to the AI provider.", confirm = true, permissions = listOf("Accessibility")),
        ToolDefinition("read_notifications", "Read latest captured notifications and send them to AI. Enable notification access first.", confirm = true, permissions = listOf("Notification access")),
        ToolDefinition("ui_click_text", "Click a visible label on the phone. Requires confirmation for every step.", confirm = true,
            permissions = listOf("Accessibility"), properties = mapOf("text" to "string:Visible label")),
        ToolDefinition("ui_type", "Type into focused phone field. Requires confirmation.", confirm = true,
            permissions = listOf("Accessibility"), properties = mapOf("text" to "string:Text to type")),
        ToolDefinition("ui_scroll", "Scroll phone screen.", confirm = true, permissions = listOf("Accessibility"), properties = mapOf("direction" to "string:up or down")),
        ToolDefinition("press_back", "Navigate back on phone.", confirm = true, permissions = listOf("Accessibility")),
        ToolDefinition("press_home", "Go to phone home screen.", confirm = true, permissions = listOf("Accessibility")),
        ToolDefinition("pc_task", "Request a desktop Friday task. Requires paired PC and confirmation. No automatic retry. Desktop handles Windows control, files, coding, documents and browser automation.",
            target = "Desktop-connected", confirm = true, properties = mapOf("text" to "string:Explicit task to execute on PC"), cancellable = true)
    )
    fun declarations() = JSONArray(definitions.map { it.json() })
}
class PhoneTools(private val context: Context) : ToolExecutor {
    private val handler = DeviceCommandHandler(context)
    override suspend fun execute(name: String, args: JSONObject): CommandResult = withContext(Dispatchers.Main) {
        try {
            val def = PhoneToolCatalog.definitions.firstOrNull { it.name == name }
                ?: return@withContext CommandResult(false, error = "Unknown tool")
            for (field in def.required) require(args.has(field) && !args.isNull(field)) { "Missing $field" }
            def.permissions.filter { it.startsWith("android.permission") }.forEach { permission ->
                if (permission != Manifest.permission.POST_NOTIFICATIONS || android.os.Build.VERSION.SDK_INT >= 33)
                    require(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) { "Enable $permission in Tools first." }
            }
            if ("Accessibility" in def.permissions) require(FridayAccessibilityService.instance != null) { "Enable Friday accessibility in Tools first." }
            val map = args.keys().asSequence().associateWith { args.get(it) }
            when (name) {
                "pc_task" -> CommandResult(false, error = "Use the desktop task channel.")
                "recall_memory" -> CommandResult(true, mapOf("memory" to com.friday.connect.pairing.PairingStorage(context).setting("memory")))
                "save_memory" -> { val text = args.getString("text"); require(text.length <= 12000)
                    com.friday.connect.pairing.PairingStorage(context).saveSetting("memory", text); CommandResult(true, mapOf("saved" to true)) }
                "open_url" -> { require(Uri.parse(args.getString("url")).scheme == "https") { "Only HTTPS websites are supported." }; handler.handle(name, map) }
                "volume_set" -> { require(args.getInt("percentage") in 0..100); handler.handle(name, map) }
                "create_reminder" -> { val minutes = args.getLong("minutes"); require(minutes in 1..525600)
                    val id = Reminders(context).add(args.getString("title"), System.currentTimeMillis() + minutes * 60000)
                    CommandResult(true, mapOf("id" to id, "timing" to "Approximate; Android may delay delivery")) }
                "list_reminders" -> CommandResult(true, mapOf("reminders" to Reminders(context).list().toString()))
                "cancel_reminder" -> CommandResult(Reminders(context).remove(args.getString("id")))
                "read_notifications" -> { require(FridayNotificationListener.enabled(context)) { "Enable notification access in Tools first." }
                    CommandResult(true, mapOf("notifications" to FridayNotificationListener.entries.value)) }
                "calendar_event" -> {
                    val duration = args.getLong("duration_minutes"); require(duration in 1..10080)
                    context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, args.getString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, args.getLong("start_ms"))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, args.getLong("start_ms") + duration * 60000)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CommandResult(true, mapOf("status" to "Calendar draft opened; user must save"))
                }
                "media_control" -> {
                    val code = when (args.getString("action")) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> error("Unsupported media command") }
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    CommandResult(true, mapOf("status" to "Media command dispatched; playback app determines result"))
                }
                else -> handler.handle(name, map)
            }
        } catch (e: Exception) { CommandResult(false, error = e.message ?: "Phone action failed") }
    }
}
