package com.friday.connect.assistant

import android.content.Context
import android.content.Intent
import com.friday.connect.accessibility.FridayAccessibilityService
import kotlinx.coroutines.delay

/** Remember only an app identifier, never proactively cache screen text. */
object AccessibilityTarget {
    fun capture(context: Context): String? {
        val service = FridayAccessibilityService.instance ?: return null
        val current = service.rootInActiveWindow?.packageName?.toString()
        return if (!current.isNullOrBlank() && current != context.packageName) current else service.lastExternalPackage
    }
    suspend fun restore(context: Context, target: String): Boolean {
        val service = FridayAccessibilityService.instance ?: return false
        if (service.rootInActiveWindow?.packageName?.toString() == target) return true
        val intent = context.packageManager.getLaunchIntentForPackage(target) ?: return false
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }.getOrElse { return false }
        repeat(20) { delay(100); if (service.rootInActiveWindow?.packageName?.toString() == target) return true }
        return false
    }
}
