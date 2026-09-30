package com.friday.connect.assistant

import java.util.Locale

object WakePolicy {
    private val trigger = Regex("(?iu)(?:\\b(?:wake\\s+up|utho|utho\\s+ji|iutho)?\\s*(?:friday|firday|fryday)(?:\\s+ai)?\\b|(?:उठो\\s*)?(?:फ्राइडे|फ्राइडै|फ्राईडे|फ्राइड़ी)(?:\\s*एआई)?)")
    fun hasWake(text: String) = trigger.containsMatchIn(text)
    fun command(text: String): String = trigger.find(text)?.let { text.substring(it.range.last + 1).trim(' ', ',', '.', '!', '?', ':') } ?: text.trim()
    fun isStop(text: String) = text.trim().lowercase(Locale.ROOT) in setOf("stop", "cancel", "stop friday", "रुको", "बंद करो", "बस")
}
