package app.aria

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Запись с микрофона: 16 кГц, mono, 16 бит. stop() возвращает готовый WAV. */
interface Recorder {
    val level: StateFlow<Float>
    suspend fun start()
    suspend fun stop(): ByteArray
}

/** Системная озвучка: Windows SAPI / Android TextToSpeech. speak() возвращается, когда фраза договорена. */
interface SystemTts {
    suspend fun speak(text: String, rate: Int, pitch: Float, voiceName: String, lang: String)
    fun stop()
    suspend fun voices(): List<String>
}

expect fun createRecorder(): Recorder
expect fun createSystemTts(): SystemTts

/** Проигрывает WAV и возвращается по окончании. При отмене корутины звук обрывается. */
expect suspend fun playWav(bytes: ByteArray)

class VoiceService {
    val recorder: Recorder = createRecorder()
    private val system: SystemTts = createSystemTts()

    /** Распознавание через Whisper-совместимый эндпоинт (OpenAI, Groq, свой whisper.cpp / faster-whisper). */
    suspend fun transcribe(wav: ByteArray, v: VoiceConfig): String {
        val base = v.endpointBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw LlmException("Не задан адрес сервера распознавания речи (Настройки → Голос)")
        val resp = Llm.http.submitFormWithBinaryData(
            url = "$base/audio/transcriptions",
            formData = formData {
                append("model", v.sttModel)
                if (v.language.isNotBlank()) append("language", v.language)
                append("response_format", "json")
                append("file", wav, Headers.build {
                    append(HttpHeaders.ContentType, "audio/wav")
                    append(HttpHeaders.ContentDisposition, "filename=\"speech.wav\"")
                })
            },
        ) {
            if (v.endpointKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${v.endpointKey}")
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw LlmException("STT HTTP ${resp.status.value}: ${text.take(300)}")
        return AppJson.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    suspend fun speak(text: String, v: VoiceConfig, role: Role) {
        when (v.ttsEngine) {
            TtsEngine.SYSTEM -> system.speak(text, role.rate, role.pitch, v.systemVoiceName, v.language)
            TtsEngine.ENDPOINT -> playWav(fetchSpeech(text, v, role))
        }
    }

    private suspend fun fetchSpeech(text: String, v: VoiceConfig, role: Role): ByteArray {
        val base = v.endpointBaseUrl.trim().trimEnd('/')
        val resp = Llm.http.post("$base/audio/speech") {
            if (v.endpointKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${v.endpointKey}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", v.ttsModel)
                put("input", text)
                put("voice", v.ttsVoice)
                put("response_format", "wav")
                put("speed", 1.0 + role.rate * 0.05)
            }.toString())
        }
        if (!resp.status.isSuccess()) throw LlmException("TTS HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
        return resp.readRawBytes()
    }

    fun stopSpeech() = system.stop()

    suspend fun systemVoices(): List<String> = system.voices()
}
