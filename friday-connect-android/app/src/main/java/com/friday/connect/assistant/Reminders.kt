package com.friday.connect.assistant

import android.app.*
import android.content.*
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.friday.connect.MainActivity
import com.friday.connect.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Reminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("friday_reminders", Context.MODE_PRIVATE)
    fun list(): JSONArray = JSONArray(prefs.getString("items", "[]"))
    @Synchronized fun add(title: String, at: Long): String {
        require(title.isNotBlank() && title.length <= 500)
        val id = UUID.randomUUID().toString(); val a = list().put(JSONObject().put("id", id).put("title", title).put("at", at))
        prefs.edit().putString("items", a.toString()).commit(); schedule(id, at); return id
    }
    @Synchronized fun remove(id: String): Boolean {
        val a = list(); val kept = JSONArray(); var found = false
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.getString("id") == id) found = true else kept.put(x) }
        prefs.edit().putString("items", kept.toString()).commit()
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(id)); return found
    }
    fun restore() { val a = list(); for (i in 0 until a.length()) { val x = a.getJSONObject(i); schedule(x.getString("id"), x.getLong("at").coerceAtLeast(System.currentTimeMillis() + 1000)) } }
    private fun pending(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(android.net.Uri.parse("friday://reminder/$id")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(id: String, at: Long) { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(id)) }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { Reminders(context).restore(); return }
        val id = intent.getStringExtra("id") ?: return; val store = Reminders(context); val a = store.list()
        val x = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("friday_reminders", "Friday reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager.notify(id.hashCode(), NotificationCompat.Builder(context, "friday_reminders").setSmallIcon(R.drawable.ic_friday_launcher)
            .setContentTitle("Friday reminder").setContentText(x.getString("title")).setStyle(NotificationCompat.BigTextStyle().bigText(x.getString("title")))
            .setContentIntent(open).setAutoCancel(true).build()) }
        store.remove(id)
    }
}
class FridayNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val e = sbn.notification.extras
        val text = "${sbn.packageName}: ${e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()} ${e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()}".take(1000)
        entries.value = (listOf(text) + entries.value).take(20)
    }
    override fun onListenerDisconnected() { entries.value = emptyList() }
    companion object {
        val entries = MutableStateFlow<List<String>>(emptyList())
        fun enabled(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
