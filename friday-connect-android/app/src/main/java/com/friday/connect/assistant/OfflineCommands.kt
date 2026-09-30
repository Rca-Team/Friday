package com.friday.connect.assistant

import org.json.JSONObject

object OfflineCommands {
    fun parse(text: String): Pair<String, JSONObject>? {
        val t = text.trim().lowercase().trimEnd('.', '!', '?')
        val simple = when (t) {
            "battery", "battery status", "phone battery", "check battery", "बैटरी", "बैटरी बताओ" -> "get_battery"
            "device info", "phone diagnostics" -> "get_device_info"
            "flashlight on", "turn on flashlight", "torch on", "टॉर्च चालू करो" -> "flashlight_on"
            "flashlight off", "turn off flashlight", "torch off", "टॉर्च बंद करो" -> "flashlight_off"
            "volume", "volume status" -> "volume_get"
            "list reminders", "my reminders" -> "list_reminders"
            else -> null
        }
        if (simple != null) return simple to JSONObject()
        Regex("(?:set )?volume(?: to)? (\\d{1,3})(?:%| percent)?").matchEntire(t)?.let {
            val value = it.groupValues[1].toInt(); if (value in 0..100) return "volume_set" to JSONObject().put("percentage", value)
        }
        Regex("remind me in (\\d+) minutes? to (.+)").matchEntire(t)?.let {
            val minutes = it.groupValues[1].toLongOrNull() ?: return null
            if (minutes in 1..525600) return "create_reminder" to JSONObject().put("minutes", minutes).put("title", text.substringAfter(" to ", it.groupValues[2]))
        }
        return null
    }
}
