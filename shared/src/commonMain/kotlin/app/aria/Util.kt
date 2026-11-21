package app.aria

import kotlin.math.sqrt
import kotlin.random.Random

fun newId(): String = nowMillis().toString(36) + Random.nextInt(0, 46656).toString(36)

/** Уровень сигнала 0..1 по 16-битному PCM little-endian. */
fun pcmLevel(buf: ByteArray, n: Int): Float {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < n) {
        val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
        sum += v.toDouble() * v
        count++
        i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sum / count)
    return (rms / 32768.0 * 8.0).coerceIn(0.0, 1.0).toFloat()
}

fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
    val out = ByteArray(44 + pcm.size)
    fun put32(o: Int, v: Int) {
        out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte()
    }
    fun put16(o: Int, v: Int) {
        out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte()
    }
    fun putStr(o: Int, s: String) {
        s.forEachIndexed { i, c -> out[o + i] = c.code.toByte() }
    }
    putStr(0, "RIFF"); put32(4, 36 + pcm.size); putStr(8, "WAVE")
    putStr(12, "fmt "); put32(16, 16); put16(20, 1); put16(22, channels)
    put32(24, sampleRate); put32(28, sampleRate * channels * 2); put16(32, channels * 2); put16(34, 16)
    putStr(36, "data"); put32(40, pcm.size)
    pcm.copyInto(out, 44)
    return out
}

/** Очистка текста перед озвучкой: убирает markdown, ремарки *...* и эмодзи. */
object SpeechText {
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val bold = Regex("\\*\\*([^*]+)\\*\\*")
    private val action = Regex("\\*[^*\\n]{1,80}\\*")
    private val emoji = Regex("[\\p{So}\\x{200D}\\x{FE0F}]")
    private val md = Regex("[*_#`>~|]+")
    private val spaces = Regex("\\s+")

    fun clean(s: String): String = s
        .replace(link, "\$1")
        .replace(bold, "\$1")
        .replace(action, "")
        .replace(emoji, "")
        .replace(md, "")
        .replace(spaces, " ")
        .trim()
}

/** Режет поток токенов на предложения, чтобы озвучка начиналась до конца ответа. Блоки кода пропускает. */
class SentenceChunker {
    private var buf = ""
    private var inCode = false

    fun feed(delta: String): List<String> {
        buf += delta
        val out = mutableListOf<String>()
        while (true) {
            if (inCode) {
                val end = buf.indexOf("```")
                if (end < 0) {
                    buf = buf.takeLast(2)
                    break
                }
                buf = buf.substring(end + 3)
                inCode = false
                continue
            }
            val fence = buf.indexOf("```")
            val cut = boundary(buf)
            if (fence >= 0 && (cut < 0 || fence < cut)) {
                val before = buf.substring(0, fence).trim()
                if (before.isNotEmpty()) out += before
                out += "Код на экране."
                buf = buf.substring(fence + 3)
                inCode = true
                continue
            }
            if (cut < 0) break
            val s = buf.substring(0, cut + 1).trim()
            buf = buf.substring(cut + 1)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    fun flush(): List<String> {
        val r = if (inCode) "" else buf.trim()
        buf = ""
        inCode = false
        return if (r.isNotEmpty()) listOf(r) else emptyList()
    }

    private fun boundary(s: String): Int {
        for (i in s.indices) {
            val c = s[i]
            if (c == '\n') return i
            val nextSpace = i + 1 < s.length && s[i + 1].isWhitespace()
            if ((c == '.' || c == '!' || c == '?' || c == '…') && nextSpace) return i
            if ((c == ',' || c == ';') && i > 160 && nextSpace) return i
        }
        return -1
    }
}
