package com.friday.connect.assistant

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import org.junit.Before
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PersistenceTests {
    @Before fun isolateConversationStore() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "assistant-conversations.json").delete()
        File(context.filesDir, "assistant-conversations.json.bak").delete()
        File(context.filesDir, "assistant-conversations.json.new").delete()
    }
    @Test fun conversationsSurviveRestartAndInterruptedWorkIsMarked() {
        val context = RuntimeEnvironment.getApplication()
        val repo = LocalConversations(context)
        val c = Conversation(title = "Persist", messages = listOf(AssistantMessage(role = "user", text = "Hello"), AssistantMessage(role = "assistant", text = "Partial answer", status = "Working")))
        repo.save(c)
        val restored = LocalConversations(context).conversations.value.first { it.id == c.id }
        assertEquals("Hello", restored.messages.first().text)
        assertEquals("Interrupted", restored.messages.last().status)
    }
    @Test fun deletingConversationRemovesPrivateAttachments() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, UUID.randomUUID().toString()).apply { writeText("private") }
        val repo = LocalConversations(context)
        val c = Conversation(messages = listOf(AssistantMessage(role = "user", text = "Inspect", attachments = listOf(Attachment("notes", "text/plain", file.absolutePath)))))
        repo.save(c); repo.delete(c.id)
        assertFalse("Unshared attachment should be deleted", file.exists())
        assertFalse("Deleted conversation should stay deleted after restart", LocalConversations(context).conversations.value.any { it.id == c.id })
    }
    @Test fun durableCommandJournalRejectsReplayAcrossRestartAndScopesDevices() {
        val context = RuntimeEnvironment.getApplication()
        RemoteCommandJournal(context).use { assertTrue(it.claim("phone-a", "request-1")); assertFalse(it.claim("phone-a", "request-1")) }
        RemoteCommandJournal(context).use { assertFalse(it.claim("phone-a", "request-1")); assertTrue(it.claim("phone-b", "request-1")) }
    }
    @Test fun remindersCanBeListedAndCancelledAfterRestart() {
        val context = RuntimeEnvironment.getApplication()
        val id = Reminders(context).add("Stand up", System.currentTimeMillis() + 60000)
        val restored = Reminders(context)
        assertTrue((0 until restored.list().length()).any { restored.list().getJSONObject(it).getString("id") == id })
        assertTrue(restored.remove(id)); assertFalse(restored.remove(id))
    }
    @Test fun simpleCommandsWorkWithoutProviderAndRejectInvalidVolume() {
        assertEquals("get_battery", OfflineCommands.parse("battery status")!!.first)
        assertEquals(20, OfflineCommands.parse("volume 20 percent")!!.second.getInt("percentage"))
        assertNull(OfflineCommands.parse("volume 200"))
        assertEquals("create_reminder", OfflineCommands.parse("remind me in 10 minutes to stretch")!!.first)
        assertNull(OfflineCommands.parse("tell me a story"))
    }
}
