package com.friday.connect.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.friday.connect.MainActivity
import com.friday.connect.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

object VoiceBus {
    val status = MutableStateFlow("Voice is off")
    val active = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val transcript = MutableStateFlow("")
    val interrupt = MutableStateFlow(0)
}
interface VoiceSession { fun pause(); fun resume(); fun tapToTalk(); fun stop() }
class FridayVoiceService : Service(), VoiceSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: AssistantEngine
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var speaking = false
    @Volatile private var capturing = false
    @Volatile private var followUntil = 0L
    @Volatile private var captureUntil = 0L
    @Volatile private var manual = false
    @Volatile private var reset = false
    private var audioJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var silencingCallback: AudioManager.AudioRecordingCallback? = null
    private var effects: AcousticEchoCanceler? = null
    override fun onCreate() {
        super.onCreate(); engine = AssistantEngine.get(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("friday_voice", "Friday voice listening", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { result -> ttsReady = result == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false; followUntil = SystemClock.elapsedRealtime() + 14000; reset = true }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false }
        })
        scope.launch { engine.lastReply.drop(1).collect { text -> if (text.isNotBlank() && engine.settings.setting("speak", "true") == "true") speak(text) } }
        scope.launch { VoiceBus.interrupt.collect { tts?.stop(); speaking = false } }
        scope.launch { engine.confirmation.collect { if (it != null) { tts?.stop(); speaking = false; VoiceBus.status.value = "Open Friday to approve the action"; notifyState() } } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stop(); return START_NOT_STICKY }
        if (intent?.action == TASK_STOP) { engine.cancel(); return START_NOT_STICKY }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoiceBus.status.value = "Microphone permission is required"; stopSelf(); return START_NOT_STICKY
        }
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(4301, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(4301, notification()) }
        catch (_: Exception) { VoiceBus.status.value = "Open Friday to start microphone listening"; stopSelf(); return START_NOT_STICKY }
        when (intent?.action) { PAUSE -> pause(); RESUME -> resume(); TAP -> tapToTalk() }
        if (audioJob == null) {
            manual = intent?.action == TAP
            VoiceBus.active.value = true; VoiceBus.paused.value = false
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Friday:voice").apply { setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) }
            // Renew in bounded intervals only while explicitly enabled.
            scope.launch { while (isActive) { delay(60 * 60 * 1000L); if (VoiceBus.active.value && !VoiceBus.paused.value) wakeLock?.acquire(2 * 60 * 60 * 1000L) } }
            audioJob = scope.launch(Dispatchers.IO) { listen() }
            if (Build.VERSION.SDK_INT >= 29) {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (configs.any { it.clientAudioSessionId == recorder?.audioSessionId && it.isClientSilenced }) {
                            VoiceBus.status.value = "Microphone is in use by another app. Start voice again when available."; stop()
                        }
                    }
                }
                silencingCallback = callback
                (getSystemService(AUDIO_SERVICE) as AudioManager).registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun listen() {
        try {
            VoiceBus.status.value = "Preparing offline voice model"
            val lang = if (engine.settings.setting("voice_language", "en") == "hi") "hi" else "en"
            val folder = File(filesDir, "voice-$lang")
            if (!File(folder, ".complete").exists()) { copyAssets("voice-$lang", folder); File(folder, ".complete").writeText("1") }
            currentCoroutineContext().ensureActive()
            model = Model(folder.absolutePath); recognizer = Recognizer(model, 16000f)
            val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission revoked")
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            check(recorder?.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            effects = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
            recorder!!.startRecording()
            if (manual) { capturing = true; captureUntil = SystemClock.elapsedRealtime() + 14000 }
            VoiceBus.status.value = if (manual) "Listening to your command" else "Say Friday to wake"
            val samples = ShortArray(2048)
            while (currentCoroutineContext().isActive) {
                if (VoiceBus.paused.value) { delay(100); continue }
                if (reset) { recognizer?.reset(); reset = false }
                val count = recorder!!.read(samples, 0, samples.size)
                if (VoiceBus.paused.value) continue
                check(count > 0) { "Microphone interrupted" }
                val now = SystemClock.elapsedRealtime()
                if (capturing && now > captureUntil) {
                    capturing = false; VoiceBus.status.value = "Command timed out. Say Friday again"
                    if (manual) { withContext(Dispatchers.Main) { stop() }; break }
                    recognizer?.reset()
                }
                val final = recognizer!!.acceptWaveForm(samples, count)
                val data = JSONObject(if (final) recognizer!!.result else recognizer!!.partialResult)
                val text = data.optString(if (final) "text" else "partial")
                if (text.isBlank()) continue
                withContext(Dispatchers.Main) { handleText(text, final) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { withContext(Dispatchers.Main) { VoiceBus.status.value = "Offline voice failed. Check microphone permission and restart voice."; stop() } }
        finally { effects?.release(); effects = null; runCatching { recorder?.stop() }; recorder?.release(); recorder = null
            recognizer?.close(); recognizer = null; model?.close(); model = null }
    }
    private fun handleText(text: String, final: Boolean) {
        if (VoiceBus.paused.value) return
        val wake = WakePolicy.hasWake(text)
        if (wake && !capturing) {
            tts?.stop(); speaking = false; engine.cancel(); capturing = true
            captureUntil = SystemClock.elapsedRealtime() + 14000; VoiceBus.status.value = "Listening to you"
        }
        if (speaking && !wake) return
        if (capturing || SystemClock.elapsedRealtime() < followUntil) VoiceBus.transcript.value = text
        if (!final) return
        if (WakePolicy.isStop(text) && (capturing || SystemClock.elapsedRealtime() < followUntil)) { engine.cancel(); capturing = false; return }
        if (capturing || SystemClock.elapsedRealtime() < followUntil) {
            val command = if (wake) WakePolicy.command(text) else text
            if (command.isNotBlank()) {
                capturing = false; followUntil = 0; reset = true
                if (!engine.busy.value) engine.send(command)
                VoiceBus.status.value = "Thinking · say Friday to interrupt"
                if (manual) { // Keep service alive for spoken response, but stop capture until next tap.
                    VoiceBus.paused.value = true; runCatching { recorder?.stop() }
                }
            } else { capturing = true; captureUntil = SystemClock.elapsedRealtime() + 14000 }
        }
        notifyState()
    }
    private fun speak(text: String) {
        if (!ttsReady) return
        val locale = if (Regex("[\\u0900-\\u097F]").containsMatchIn(text)) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        val available = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (available < 0) { VoiceBus.status.value = "Install a ${locale.displayLanguage} text-to-speech voice in Android settings"; return }
        tts?.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
    override fun pause() { VoiceBus.paused.value = true; tts?.stop(); speaking = false; capturing = false; followUntil = 0
        runCatching { recorder?.stop() }; if (wakeLock?.isHeld == true) wakeLock?.release(); VoiceBus.status.value = "Voice paused"; notifyState() }
    override fun resume() { manual = false; reset = true; runCatching { recorder?.startRecording() }; VoiceBus.paused.value = false
        if (wakeLock?.isHeld == false) wakeLock?.acquire(2 * 60 * 60 * 1000L); VoiceBus.status.value = "Say Friday to wake"; notifyState() }
    override fun tapToTalk() { tts?.stop(); speaking = false; engine.cancel(); VoiceBus.paused.value = false; runCatching { recorder?.startRecording() }
        capturing = true; reset = true; captureUntil = SystemClock.elapsedRealtime() + 14000; VoiceBus.status.value = "Listening to your command"; notifyState() }
    override fun stop() { stopSelf() }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, FridayVoiceService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "friday_voice").setSmallIcon(R.drawable.ic_friday_launcher).setContentTitle("Friday microphone")
            .setContentText(VoiceBus.status.value).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, if (VoiceBus.paused.value) "Resume" else "Pause", action(if (VoiceBus.paused.value) RESUME else PAUSE))
            .addAction(0, "Stop task", action(TASK_STOP))
            .addAction(0, "Stop", action(STOP)).build()
    }
    private fun notifyState() { getSystemService(NotificationManager::class.java).notify(4301, notification()) }
    private fun copyAssets(path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) { target.parentFile?.mkdirs(); assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } } }
        else { target.mkdirs(); children.forEach { copyAssets("$path/$it", File(target, it)) } }
    }
    override fun onDestroy() {
        audioJob?.cancel(); runCatching { recorder?.stop() }; scope.cancel(); tts?.stop(); tts?.shutdown()
        if (Build.VERSION.SDK_INT >= 29) silencingCallback?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(it) }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        VoiceBus.active.value = false; VoiceBus.paused.value = false
        if (!VoiceBus.status.value.contains("failed") && !VoiceBus.status.value.contains("Microphone")) VoiceBus.status.value = "Voice is off · start from the app"
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val START = "voice.START"; const val TAP = "voice.TAP"; const val PAUSE = "voice.PAUSE"; const val RESUME = "voice.RESUME"; const val STOP = "voice.STOP"; const val TASK_STOP = "voice.TASK_STOP" }
}
