package app.aria

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** Устанавливается в MainActivity.onCreate до первого обращения к AriaApp.controller. */
object AndroidEnv {
    lateinit var app: Application
}

actual fun dataDir(): String = AndroidEnv.app.filesDir.absolutePath

actual fun readTextFile(path: String): String? = File(path).takeIf { it.exists() }?.readText(Charsets.UTF_8)

actual fun writeTextFile(path: String, text: String) {
    val f = File(path)
    val tmp = File("$path.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(f)) {
        f.writeText(text, Charsets.UTF_8)
        tmp.delete()
    }
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun createRecorder(): Recorder = AndroidRecorder()
actual fun createSystemTts(): SystemTts = AndroidTts()

// ---------------------------------------------------------------- микрофон
private class AndroidRecorder : Recorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private var rec: AudioRecord? = null
    private var job: Job? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (AndroidEnv.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("нет разрешения на микрофон (Настройки Android → Приложения → Aria)")
        }
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("микрофон недоступен")
        }
        synchronized(pcm) { pcm.reset() }
        rec = r
        running = true
        r.startRecording()
        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(2048)
            while (running && isActive) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                synchronized(pcm) { pcm.write(buf, 0, n) }
                _level.value = pcmLevel(buf, n)
            }
        }
    }

    override suspend fun stop(): ByteArray {
        running = false
        job?.join()
        job = null
        rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        rec = null
        _level.value = 0f
        val data = synchronized(pcm) { pcm.toByteArray() }
        return pcmToWav(data, 16000)
    }
}

// ---------------------------------------------------------------- системная озвучка
private class AndroidTts : SystemTts {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(AndroidEnv.app) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    override suspend fun speak(text: String, rate: Int, pitch: Float, voiceName: String, lang: String) {
        if (!ready.await()) throw IllegalStateException("на устройстве нет движка синтеза речи")
        tts.setLanguage(Locale.forLanguageTag(lang))
        if (voiceName.isNotBlank()) {
            tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
        }
        tts.setSpeechRate((1f + rate * 0.08f).coerceIn(0.5f, 1.6f))
        tts.setPitch(pitch)
        val id = newId()
        suspendCancellableCoroutine<Unit> { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    override fun stop() {
        tts.stop()
    }

    override suspend fun voices(): List<String> {
        if (!ready.await()) return emptyList()
        return tts.voices?.filter { it.locale.language == "ru" || it.locale.language == "en" }
            ?.map { it.name }?.sorted() ?: emptyList()
    }
}

// ---------------------------------------------------------------- проигрывание WAV
actual suspend fun playWav(bytes: ByteArray) {
    val f = File.createTempFile("aria", ".wav", AndroidEnv.app.cacheDir)
    f.writeBytes(bytes)
    try {
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() } }
            mp.prepare()
            mp.start()
        }
    } finally {
        f.delete()
    }
}
