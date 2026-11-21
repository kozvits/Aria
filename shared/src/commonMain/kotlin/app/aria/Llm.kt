package app.aria

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class LlmException(message: String) : Exception(message)

data class Turn(val fromUser: Boolean, val text: String)

interface LlmClient {
    suspend fun listModels(): List<String>
    fun stream(system: String, turns: List<Turn>, temperature: Double?, model: String): Flow<String>
}

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

object Llm {
    val http: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 120_000
        }
    }
}

fun createLlm(cfg: ProviderConfig): LlmClient = when (cfg.type) {
    ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(cfg)
    ProviderType.ANTHROPIC -> AnthropicClient(cfg)
    ProviderType.GEMINI -> GeminiClient(cfg)
}

internal fun HttpRequestBuilder.jsonBody(body: JsonObject) {
    contentType(ContentType.Application.Json)
    setBody(body.toString())
}

/** Универсальный разбор SSE: из каждой строки data: вытаскиваем кусочек текста. */
internal fun sseFlow(
    url: String,
    configure: HttpRequestBuilder.() -> Unit,
    parse: (String) -> String?,
): Flow<String> = channelFlow {
    Llm.http.preparePost(url) { configure() }.execute { resp ->
        if (!resp.status.isSuccess()) {
            throw LlmException("HTTP ${resp.status.value}: ${resp.bodyAsText().take(500)}")
        }
        val ch = resp.bodyAsChannel()
        while (true) {
            val line = ch.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            val piece = try {
                parse(data)
            } catch (e: LlmException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!piece.isNullOrEmpty()) send(piece)
        }
    }
}

internal suspend fun getJson(url: String, configure: HttpRequestBuilder.() -> Unit): JsonObject {
    val r = Llm.http.get(url) { configure() }
    val text = r.bodyAsText()
    if (!r.status.isSuccess()) throw LlmException("HTTP ${r.status.value}: ${text.take(300)}")
    return AppJson.parseToJsonElement(text).jsonObject
}

// ---------------------------------------------------------------- OpenAI-совместимые
// OpenRouter, OpenAI, Groq, DeepSeek, Mistral, Together, Ollama, LM Studio, vLLM и т.д.
class OpenAiCompatibleClient(private val cfg: ProviderConfig) : LlmClient {
    private val base = cfg.baseUrl.trim().trimEnd('/')

    private fun HttpRequestBuilder.auth() {
        if (cfg.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${cfg.apiKey}")
        if (base.contains("openrouter.ai")) {
            header("HTTP-Referer", "https://github.com/aria-assistant")
            header("X-Title", "Aria")
        }
    }

    override suspend fun listModels(): List<String> {
        val j = getJson("$base/models") { auth() }
        return j["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?.sorted() ?: emptyList()
    }

    override fun stream(system: String, turns: List<Turn>, temperature: Double?, model: String): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (temperature != null) put("temperature", temperature)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        put("content", t.text)
                    })
                }
            }
        }
        return sseFlow("$base/chat/completions", { auth(); jsonBody(body) }) { data ->
            val el = AppJson.parseToJsonElement(data).jsonObject
            el["error"]?.let { throw LlmException(it.toString().take(300)) }
            el["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }
    }
}

// ---------------------------------------------------------------- Anthropic
class AnthropicClient(private val cfg: ProviderConfig) : LlmClient {
    private val base = cfg.baseUrl.trim().trimEnd('/')

    private fun HttpRequestBuilder.auth() {
        header("x-api-key", cfg.apiKey)
        header("anthropic-version", "2023-06-01")
    }

    override suspend fun listModels(): List<String> {
        val j = getJson("$base/v1/models?limit=100") { auth() }
        return j["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
    }

    /** Anthropic требует чередования ролей и начала с user. */
    private fun alternating(turns: List<Turn>): List<Turn> {
        val out = mutableListOf<Turn>()
        for (t in turns) {
            if (out.isEmpty() && !t.fromUser) continue
            if (out.isNotEmpty() && out.last().fromUser == t.fromUser) {
                out[out.lastIndex] = Turn(t.fromUser, out.last().text + "\n\n" + t.text)
            } else out += t
        }
        return out
    }

    override fun stream(system: String, turns: List<Turn>, temperature: Double?, model: String): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("stream", true)
            put("system", system)
            if (temperature != null) put("temperature", temperature.coerceAtMost(1.0))
            putJsonArray("messages") {
                alternating(turns).forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        put("content", t.text)
                    })
                }
            }
        }
        return sseFlow("$base/v1/messages", { auth(); jsonBody(body) }) { data ->
            val el = AppJson.parseToJsonElement(data).jsonObject
            when (el["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> el["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                "error" -> throw LlmException(el["error"]?.toString()?.take(300) ?: "ошибка Anthropic")
                else -> null
            }
        }
    }
}

// ---------------------------------------------------------------- Google Gemini
class GeminiClient(private val cfg: ProviderConfig) : LlmClient {
    private val base = cfg.baseUrl.trim().trimEnd('/')

    private fun HttpRequestBuilder.auth() {
        header("x-goog-api-key", cfg.apiKey)
    }

    override suspend fun listModels(): List<String> {
        val j = getJson("$base/v1beta/models?pageSize=200") { auth() }
        return j["models"]?.jsonArray?.mapNotNull { m ->
            val o = m.jsonObject
            val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if ("generateContent" in methods) o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") else null
        }?.sorted() ?: emptyList()
    }

    override fun stream(system: String, turns: List<Turn>, temperature: Double?, model: String): Flow<String> {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromUser) "user" else "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", t.text) }) }
                    })
                }
            }
            if (temperature != null) putJsonObject("generationConfig") { put("temperature", temperature) }
        }
        return sseFlow("$base/v1beta/models/$model:streamGenerateContent?alt=sse", { auth(); jsonBody(body) }) { data ->
            val el = AppJson.parseToJsonElement(data).jsonObject
            el["error"]?.let { throw LlmException(it.toString().take(300)) }
            el["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
        }
    }
}
