package com.friday.connect.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.friday.connect.core.FridayConnectCapabilities
import com.friday.connect.core.CommandResult
import com.friday.connect.device.AndroidDeviceInfoProvider

class DeviceCommandHandler(private val context: Context) {
    private val infoProvider = AndroidDeviceInfoProvider(context)

    fun handle(action: String, parameters: Map<String, Any?>): CommandResult {
        return when (action.lowercase()) {
            "get_device_info" -> getDeviceInfo()
            "get_battery" -> getBattery()
            "flashlight_on" -> flashlight(true)
            "flashlight_off" -> flashlight(false)
            "launch_app" -> launchApp(parameters)
            "open_url" -> openUrl(parameters)
            "volume_get" -> volumeGet()
            "volume_set" -> volumeSet(parameters)
            "call_phone", "open_dialer" -> openDialer(parameters)
            "compose_sms", "send_message" -> composeSms(parameters)
            "unlock_phone" -> unlockPhone(parameters)
            "file_list" -> fileList(parameters)
            "file_read" -> fileRead(parameters)
            "file_write" -> fileWrite(parameters)
            "file_delete" -> fileDelete(parameters)
            "download_from_pc" -> downloadFromPc(parameters)
            "ui_dump" -> uiDump()
            "ui_tap" -> uiTap(parameters)
            "ui_swipe" -> uiSwipe(parameters)
            "ui_type" -> uiType(parameters)
            "ui_click_text" -> uiClickText(parameters)
            "ui_click_id" -> uiClickId(parameters)
            "ui_find_and_type" -> uiFindAndType(parameters)
            "ui_scroll" -> uiScroll(parameters)
            "press_back" -> pressBack()
            "press_home" -> pressHome()
            "press_recents" -> pressRecents()
            "open_notifications" -> openNotifications()
            "open_quick_settings" -> openQuickSettings()
            "read_screen_text" -> readScreenText()
            "list_installed_apps" -> listInstalledApps()
            "clipboard_get" -> clipboardGet()
            "clipboard_set" -> clipboardSet(parameters)
            "run_workflow" -> runWorkflow(parameters)
            else -> CommandResult(false, errorCode = "UNKNOWN_COMMAND", error = "Unsupported command: $action")
        }
    }

    private fun getDeviceInfo(): CommandResult {
        val battery = infoProvider.batterySnapshot()
        return CommandResult(
            success = true,
            data = mapOf(
                "device_id" to null,
                "device_name" to (Build.MODEL ?: "Android"),
                "platform" to "android",
                "android_version" to (Build.VERSION.RELEASE ?: "Unknown"),
                "agent_version" to "1.0.0",
                "model" to (Build.MODEL ?: "Android"),
                "battery" to battery.first,
                "charging" to battery.second,
                "wifi_state" to if (infoProvider.wifiEnabled()) "connected" else "disconnected",
                "capabilities" to FridayConnectCapabilities.INITIAL,
            ),
        )
    }

    private fun getBattery(): CommandResult {
        val battery = infoProvider.batterySnapshot()
        return CommandResult(true, data = mapOf("percentage" to battery.first, "charging" to battery.second))
    }

    private fun flashlight(enabled: Boolean): CommandResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CommandResult(
                false,
                errorCode = "CAMERA_PERMISSION_REQUIRED",
                error = "Camera permission is required to control the flashlight.",
            )
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return CommandResult(false, errorCode = "FLASHLIGHT_UNAVAILABLE", error = "Camera service unavailable.")
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                flash && (facing == CameraCharacteristics.LENS_FACING_BACK || facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
            }.getOrDefault(false)
        } ?: return CommandResult(false, errorCode = "FLASHLIGHT_UNAVAILABLE", error = "No flashlight on this device.")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val maxStrength = runCatching {
                    manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                }.getOrDefault(1)
                if (enabled && maxStrength > 1) {
                    manager.turnOnTorchWithStrengthLevel(cameraId, maxStrength)
                } else {
                    manager.setTorchMode(cameraId, enabled)
                }
            } else {
                manager.setTorchMode(cameraId, enabled)
            }
            CommandResult(true, data = mapOf("flashlight" to if (enabled) "on" else "off"))
        } catch (security: SecurityException) {
            CommandResult(false, errorCode = "FLASHLIGHT_UNAVAILABLE", error = security.message ?: "Flashlight permission denied.")
        } catch (exc: Exception) {
            CommandResult(false, errorCode = "FLASHLIGHT_UNAVAILABLE", error = exc.message ?: "Flashlight unavailable.")
        }
    }

    private fun launchApp(parameters: Map<String, Any?>): CommandResult {
        val requested = listOf("package", "package_name", "app_name", "app", "name")
            .firstNotNullOfOrNull { key -> parameters[key]?.toString()?.trim() }
            .orEmpty()
        if (requested.isBlank()) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "App name or package name is required.")
        }

        val packageName = resolveLaunchablePackage(requested)
            ?: requested.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return CommandResult(false, errorCode = "APP_NOT_FOUND", error = "Package not found: $requested")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return CommandResult(false, errorCode = "APP_NOT_FOUND", error = "Package not found: $packageName")
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(true, data = mapOf("package" to packageName, "app_name" to requested))
        } catch (exc: Exception) {
            CommandResult(false, errorCode = "APP_NOT_FOUND", error = exc.message ?: "Unable to launch app.")
        }
    }

    private fun resolveLaunchablePackage(requested: String): String? {
        val normalizedRequested = normalizeAppQuery(requested)
        if (normalizedRequested.isBlank()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = runCatching {
            context.packageManager.queryIntentActivities(launcherIntent, 0)
        }.getOrDefault(emptyList())

        fun labelOf(info: ResolveInfo): String {
            return info.loadLabel(context.packageManager)?.toString().orEmpty()
        }

        val exactMatch = launchableApps.firstOrNull { info ->
            val label = normalizeAppQuery(labelOf(info))
            val packageId = normalizeAppQuery(info.activityInfo.packageName)
            normalizedRequested == label || normalizedRequested == packageId
        }
        if (exactMatch != null) return exactMatch.activityInfo.packageName

        val containsMatch = launchableApps.firstOrNull { info ->
            val label = normalizeAppQuery(labelOf(info))
            val packageId = normalizeAppQuery(info.activityInfo.packageName)
            label.contains(normalizedRequested) ||
                normalizedRequested.contains(label) ||
                packageId.contains(normalizedRequested) ||
                normalizedRequested.contains(packageId)
        }
        return containsMatch?.activityInfo?.packageName
    }

    private fun normalizeAppQuery(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun openUrl(parameters: Map<String, Any?>): CommandResult {
        val url = (parameters["url"] ?: parameters["link"])?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "URL is required.")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(true, data = mapOf("url" to url))
        } catch (exc: Exception) {
            CommandResult(false, errorCode = "OPEN_URL_FAILED", error = exc.message ?: "Unable to open URL.")
        }
    }

    private fun volumeGet(): CommandResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandResult(false, errorCode = "AUDIO_UNAVAILABLE", error = "Audio service unavailable.")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percentage = ((current * 100f) / max).toInt().coerceIn(0, 100)
        return CommandResult(true, data = mapOf("stream" to "music", "percentage" to percentage, "current" to current, "max" to max))
    }

    private fun volumeSet(parameters: Map<String, Any?>): CommandResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CommandResult(false, errorCode = "AUDIO_UNAVAILABLE", error = "Audio service unavailable.")
        val targetRaw = parameters["value"] ?: parameters["percentage"]
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "Volume value is required.")
        val target = when (targetRaw) {
            is Number -> targetRaw.toInt()
            else -> targetRaw.toString().toIntOrNull()
        } ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "Volume value must be numeric.")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clamped = target.coerceIn(0, 100)
        val level = ((clamped / 100f) * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        return CommandResult(true, data = mapOf("stream" to "music", "percentage" to clamped, "current" to level, "max" to max))
    }

    private fun unlockPhone(parameters: Map<String, Any?>): CommandResult {
        // Android intentionally prevents apps and Accessibility services from
        // bypassing a PIN, fingerprint, or face lock. Never accept a lock PIN
        // over the PC connection or report an unlock that did not happen.
        return CommandResult(
            false,
            errorCode = "UNLOCK_REQUIRES_DEVICE_AUTH",
            error = "Unlock the phone directly with its configured PIN, fingerprint, or face authentication."
        )
    }

    private fun openDialer(parameters: Map<String, Any?>): CommandResult {
        val number = (parameters["number"] ?: parameters["phone"] ?: parameters["recipient"])
            ?.toString()?.trim().orEmpty()
        if (number.isBlank()) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "A phone number is required.")
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CommandResult(true, data = mapOf("number" to number, "status" to "dialer_opened"))
        } catch (exc: Exception) {
            CommandResult(false, errorCode = "CALL_UNAVAILABLE", error = exc.message ?: "Unable to open the phone dialer.")
        }
    }

    private fun composeSms(parameters: Map<String, Any?>): CommandResult {
        val number = (parameters["number"] ?: parameters["phone"] ?: parameters["recipient"])
            ?.toString()?.trim().orEmpty()
        val message = (parameters["message"] ?: parameters["text"] ?: parameters["body"])
            ?.toString().orEmpty()
        if (number.isBlank()) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "A phone number is required.")
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CommandResult(true, data = mapOf("number" to number, "status" to "message_ready_for_review"))
        } catch (exc: Exception) {
            CommandResult(false, errorCode = "MESSAGING_UNAVAILABLE", error = exc.message ?: "Unable to open the messaging app.")
        }
    }

    private fun resolveFileTarget(path: String?): java.io.File {
        val storage = android.os.Environment.getExternalStorageDirectory()
        if (path.isNullOrBlank() || path.equals("home", ignoreCase = true)) {
            return storage
        }
        val lower = path.lowercase().trim()
        val baseDir = when (lower) {
            "downloads" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            "documents" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            "pictures" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            "music" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
            "movies", "videos" -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            else -> java.io.File(storage, path)
        }
        return baseDir
    }

    private fun fileList(parameters: Map<String, Any?>): CommandResult {
        val path = parameters["path"]?.toString()
        val dir = resolveFileTarget(path)
        if (!dir.exists() || !dir.isDirectory) {
            return CommandResult(false, errorCode = "NOT_FOUND", error = "Directory not found: ${dir.absolutePath}")
        }
        val items = dir.listFiles()?.map { file ->
            mapOf(
                "name" to file.name,
                "is_dir" to file.isDirectory,
                "size" to file.length(),
                "path" to file.absolutePath
            )
        } ?: emptyList()
        return CommandResult(true, data = mapOf("items" to items, "path" to dir.absolutePath))
    }

    private fun fileRead(parameters: Map<String, Any?>): CommandResult {
        val path = parameters["path"]?.toString()
        val file = resolveFileTarget(path)
        if (!file.exists() || !file.isFile) {
            return CommandResult(false, errorCode = "NOT_FOUND", error = "File not found: ${file.absolutePath}")
        }
        return try {
            val content = file.readText()
            CommandResult(true, data = mapOf("content" to content, "path" to file.absolutePath))
        } catch (e: Exception) {
            CommandResult(false, errorCode = "READ_ERROR", error = e.message ?: "Failed to read file.")
        }
    }

    private fun fileWrite(parameters: Map<String, Any?>): CommandResult {
        val path = parameters["path"]?.toString()
        val content = parameters["content"]?.toString() ?: ""
        val append = parameters["append"] as? Boolean ?: false
        val file = resolveFileTarget(path)
        return try {
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            CommandResult(true, data = mapOf("message" to "File written successfully.", "path" to file.absolutePath))
        } catch (e: Exception) {
            CommandResult(false, errorCode = "WRITE_ERROR", error = e.message ?: "Failed to write file.")
        }
    }

    private fun fileDelete(parameters: Map<String, Any?>): CommandResult {
        val path = parameters["path"]?.toString()
        val file = resolveFileTarget(path)
        if (!file.exists()) {
            return CommandResult(false, errorCode = "NOT_FOUND", error = "File or directory not found: ${file.absolutePath}")
        }
        return try {
            val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (success) {
                CommandResult(true, data = mapOf("message" to "Deleted successfully.", "path" to file.absolutePath))
            } else {
                CommandResult(false, errorCode = "DELETE_FAILED", error = "Failed to delete.")
            }
        } catch (e: Exception) {
            CommandResult(false, errorCode = "DELETE_ERROR", error = e.message ?: "Failed to delete file.")
        }
    }

    private fun uiDump(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
        if (service == null) {
            return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        }
        val result = service.dumpUiTree()
        return if (result.containsKey("error")) {
            CommandResult(false, errorCode = "UI_DUMP_FAILED", error = result["error"] as String)
        } else {
            CommandResult(true, data = result)
        }
    }

    private fun uiTap(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
        if (service == null) {
            return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        }
        val x = (parameters["x"] as? Number)?.toInt()
        val y = (parameters["y"] as? Number)?.toInt()
        if (x == null || y == null) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "x and y coordinates are required.")
        }
        val success = service.tap(x, y)
        return CommandResult(success, data = mapOf("action" to "tap", "success" to success))
    }

    private fun uiSwipe(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
        if (service == null) {
            return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        }
        val x1 = (parameters["x1"] as? Number)?.toInt()
        val y1 = (parameters["y1"] as? Number)?.toInt()
        val x2 = (parameters["x2"] as? Number)?.toInt()
        val y2 = (parameters["y2"] as? Number)?.toInt()
        val duration = (parameters["duration"] as? Number)?.toLong() ?: 300L
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "x1, y1, x2, y2 coordinates are required.")
        }
        val success = service.swipe(x1, y1, x2, y2, duration)
        return CommandResult(success, data = mapOf("action" to "swipe", "success" to success))
    }

    private fun uiType(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
        if (service == null) {
            return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        }
        val text = parameters["text"]?.toString()
        if (text.isNullOrEmpty()) {
            return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "text is required.")
        }
        val success = service.typeText(text)
        return CommandResult(success, data = mapOf("action" to "type", "success" to success))
    }

    private fun downloadFromPc(parameters: Map<String, Any?>): CommandResult {
        val pcPath = parameters["path"]?.toString()
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "path parameter is required.")
        val credential = com.friday.connect.pairing.PairingStorage(context).loadCredential()
            ?: return CommandResult(false, errorCode = "NOT_PAIRED", error = "Pair with Friday first.")
        val cleanGateway = runCatching { com.friday.connect.assistant.GatewaySecurity.base(credential) }.getOrElse { return CommandResult(false, error = it.message) }
        val encodedPath = Uri.encode(pcPath)
        val downloadUri = Uri.parse("$cleanGateway/api/remote/files/download?path=$encodedPath")

        val filename = parameters["filename"]?.toString()
            ?: (Uri.parse(pcPath).lastPathSegment?.let { if (it.contains(".")) it else "$it.zip" } ?: "friday_download.zip")

        return try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(downloadUri)
                .addRequestHeader("X-Friday-Device", credential.deviceId)
                .addRequestHeader("Authorization", "Bearer ${credential.deviceSecret}")
                .setTitle("Friday: $filename")
                .setDescription("Downloading $filename directly from PC")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Friday/$filename")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val downloadId = downloadManager.enqueue(request)
            CommandResult(true, data = mapOf("download_id" to downloadId, "filename" to filename, "status" to "queued"))
        } catch (e: Exception) {
            CommandResult(false, errorCode = "DOWNLOAD_FAILED", error = e.message ?: "Failed to initiate download.")
        }
    }

    private fun uiClickText(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val text = parameters["text"]?.toString()
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "text is required.")
        val exact = (parameters["exact"] as? Boolean) ?: false
        val success = service.clickText(text, exact)
        return CommandResult(success, data = mapOf("action" to "click_text", "target" to text, "clicked" to success))
    }

    private fun uiClickId(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val id = parameters["id"]?.toString()
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "id is required.")
        val success = service.clickId(id)
        return CommandResult(success, data = mapOf("action" to "click_id", "target" to id, "clicked" to success))
    }

    private fun uiFindAndType(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val text = parameters["text"]?.toString()
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "text is required.")
        val target = parameters["target"]?.toString()
        val success = service.typeTextIntoField(target, text)
        return CommandResult(success, data = mapOf("action" to "find_and_type", "text" to text, "typed" to success))
    }

    private fun uiScroll(parameters: Map<String, Any?>): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val direction = parameters["direction"]?.toString() ?: "down"
        val success = service.scroll(direction)
        return CommandResult(success, data = mapOf("action" to "scroll", "direction" to direction, "scrolled" to success))
    }

    private fun pressBack(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val success = service.pressBack()
        return CommandResult(success, data = mapOf("action" to "press_back", "success" to success))
    }

    private fun pressHome(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val success = service.pressHome()
        return CommandResult(success, data = mapOf("action" to "press_home", "success" to success))
    }

    private fun pressRecents(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val success = service.pressRecents()
        return CommandResult(success, data = mapOf("action" to "press_recents", "success" to success))
    }

    private fun openNotifications(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val success = service.openNotifications()
        return CommandResult(success, data = mapOf("action" to "open_notifications", "success" to success))
    }

    private fun openQuickSettings(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val success = service.openQuickSettings()
        return CommandResult(success, data = mapOf("action" to "open_quick_settings", "success" to success))
    }

    private fun readScreenText(): CommandResult {
        val service = com.friday.connect.accessibility.FridayAccessibilityService.instance
            ?: return CommandResult(false, errorCode = "ACCESSIBILITY_DISABLED", error = "Friday Accessibility Service is not enabled.")
        val list = service.readScreenText()
        return CommandResult(true, data = mapOf("visible_text" to list, "count" to list.size))
    }

    private fun listInstalledApps(): CommandResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, 0).map { ri ->
            mapOf(
                "name" to ri.loadLabel(pm).toString(),
                "package" to ri.activityInfo.packageName
            )
        }.sortedBy { it["name"] }
        return CommandResult(true, data = mapOf("apps" to apps, "total" to apps.size))
    }

    private fun clipboardGet(): CommandResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return CommandResult(true, data = mapOf("text" to text))
    }

    private fun clipboardSet(parameters: Map<String, Any?>): CommandResult {
        val text = parameters["text"]?.toString() ?: ""
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Friday", text)
        cm?.setPrimaryClip(clip)
        return CommandResult(true, data = mapOf("text" to text, "copied" to true))
    }

    private fun runWorkflow(parameters: Map<String, Any?>): CommandResult {
        val steps = (parameters["steps"] as? List<*>)
            ?: return CommandResult(false, errorCode = "INVALID_ARGUMENT", error = "steps list is required.")
        if (steps.size > 12) return CommandResult(false, error = "Workflow exceeds 12 steps.")
        val token = AutomationGuard.token()
        val deadline = android.os.SystemClock.elapsedRealtime() + 60000
        val results = mutableListOf<Map<String, Any?>>()
        for (step in steps) {
            if (!AutomationGuard.active(token) || android.os.SystemClock.elapsedRealtime() > deadline) return CommandResult(false, error = "Workflow stopped or timed out.")
            if (step is Map<*, *>) {
                val stepMap = step.entries.associate { it.key.toString() to it.value }
                val action = stepMap["action"]?.toString() ?: continue
                if (action == "run_workflow") return CommandResult(false, error = "Nested workflows are not allowed.")
                val waitMs = ((stepMap["wait_ms"] as? Number)?.toLong() ?: 0L).coerceIn(0, 2000)
                if (waitMs > 0) {
                    try { Thread.sleep(waitMs) } catch (_: InterruptedException) { return CommandResult(false, error = "Workflow interrupted.") }
                }
                if (!AutomationGuard.active(token)) return CommandResult(false, error = "Workflow stopped.")
                val res = handle(action, stepMap)
                results.add(mapOf("action" to action, "success" to res.success, "data" to res.data, "error" to res.error))
                if (!res.success && (stepMap["stop_on_failure"] as? Boolean == true)) {
                    return CommandResult(false, errorCode = "WORKFLOW_STEP_FAILED", error = "Failed at step $action", data = mapOf("results" to results))
                }
            }
        }
        return CommandResult(true, data = mapOf("workflow_completed" to true, "steps_executed" to results.size, "results" to results))
    }
}
