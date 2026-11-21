package app.aria

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine

actual fun dataDir(): String {
    val base = System.getenv("APPDATA") ?: (System.getProperty("user.home") + File.separator + ".config")
    val dir = File(base, "Aria")
    dir.mkdirs()
    return dir.absolutePath
}

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

actual fun createRecorder(): Recorder = DesktopRecorder()
actual fun createSystemTts(): SystemTts = WindowsTts()

// ---------------------------------------------------------------- микрофон (javax.sound)
private class DesktopRecorder : Recorder {
    private val format = AudioFormat(16000f, 16, 1, true, false)
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private var line: TargetDataLine? = null
    private var job: Job? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    override suspend fun start() {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("Микрофон не найден")
        val l = AudioSystem.getLine(info) as TargetDataLine
        l.open(format)
        l.start()
        line = l
        synchronized(pcm) { pcm.reset() }
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(2048)
            while (running && isActive) {
                val n = try {
                    l.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
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
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }
        line = null
        _level.value = 0f
        val data = synchronized(pcm) { pcm.toByteArray() }
        return pcmToWav(data, 16000)
    }
}

// ---------------------------------------------------------------- озвучка: Windows SAPI через постоянный процесс PowerShell
private val PS_SCRIPT = """
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
~s = New-Object System.Speech.Synthesis.SpeechSynthesizer
~s.SetOutputToDefaultAudioDevice()
[Console]::Out.WriteLine('READY')
while (~true) {
  ~line = [Console]::In.ReadLine()
  if (~line -eq ~null) { break }
  ~p = ~line.Split('|')
  try {
    ~s.Rate = [int]~p[0]
    ~ok = ~false
    if (~p[1] -ne '') { try { ~s.SelectVoice(~p[1]); ~ok = ~true } catch {} }
    if (-not ~ok) {
      try {
        ~s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::Female, [System.Speech.Synthesis.VoiceAge]::Adult, 0, (New-Object System.Globalization.CultureInfo(~p[2])))
      } catch {}
    }
    ~t = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(~p[3]))
    ~s.Speak(~t)
  } catch { [Console]::Out.WriteLine('ERR ' + ~_.Exception.Message) }
  [Console]::Out.WriteLine('DONE')
}
""".trimIndent().replace('~', '$')

private class WindowsTts : SystemTts {
    private val lock = Any()
    private var proc: Process? = null
    private var out: BufferedWriter? = null
    private var inp: BufferedReader? = null

    @Volatile
    private var generation = 0

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun culture(lang: String): String = when {
        lang.contains('-') -> lang
        lang == "ru" -> "ru-RU"
        lang == "en" -> "en-US"
        lang == "de" -> "de-DE"
        lang == "fr" -> "fr-FR"
        lang == "es" -> "es-ES"
        lang == "uk" -> "uk-UA"
        else -> lang
    }

    /** Вызывать под lock. */
    private fun ensure() {
        if (proc?.isAlive == true) return
        val script = File(dataDir(), "tts.ps1")
        script.writeText(PS_SCRIPT, Charsets.UTF_8)
        val p = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
        ).redirectErrorStream(true).start()
        val w = p.outputStream.bufferedWriter(Charsets.UTF_8)
        val r = p.inputStream.bufferedReader(Charsets.UTF_8)
        var ready = false
        repeat(20) {
            if (!ready) {
                val line = r.readLine() ?: throw IllegalStateException("PowerShell не запустился")
                if (line.trim() == "READY") ready = true
            }
        }
        if (!ready) throw IllegalStateException("Синтезатор речи не ответил")
        proc = p; out = w; inp = r
    }

    override suspend fun speak(text: String, rate: Int, pitch: Float, voiceName: String, lang: String) {
        if (!isWindows) throw IllegalStateException("Системный голос доступен только в Windows. Выберите «Сервер» в настройках голоса")
        withContext(Dispatchers.IO) {
            val myGen = generation
            val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            val reader: BufferedReader = synchronized(lock) {
                ensure()
                val safeVoice = voiceName.replace('|', ' ').trim()
                out!!.write("${rate.coerceIn(-10, 10)}|$safeVoice|${culture(lang)}|$b64\n")
                out!!.flush()
                inp!!
            }
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("ERR")) throw IllegalStateException(line.removePrefix("ERR").trim())
                    if (line == "DONE") break
                }
            } catch (e: IOException) {
                if (generation == myGen) throw e // иначе это обычная остановка речи
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            runCatching { proc?.destroyForcibly() }
            proc = null; out = null; inp = null
        }
    }

    override suspend fun voices(): List<String> = withContext(Dispatchers.IO) {
        if (!isWindows) return@withContext emptyList()
        runCatching {
            val cmd = "Add-Type -AssemblyName System.Speech; " +
                "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices() | ForEach-Object { ~_.VoiceInfo.Name }"
            val p = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-Command", cmd.replace('~', '$'))
                .redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            p.waitFor()
            lines
        }.getOrDefault(emptyList())
    }
}

// ---------------------------------------------------------------- проигрывание WAV
actual suspend fun playWav(bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        val ais = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        val clip = AudioSystem.getClip()
        try {
            val done = CompletableDeferred<Unit>()
            clip.addLineListener { if (it.type == LineEvent.Type.STOP) done.complete(Unit) }
            clip.open(ais)
            clip.start()
            done.await()
        } finally {
            runCatching { clip.stop() }
            runCatching { clip.close() }
            runCatching { ais.close() }
        }
    }
}
