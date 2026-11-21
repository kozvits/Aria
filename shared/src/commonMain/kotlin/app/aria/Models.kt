package app.aria

import kotlinx.serialization.Serializable

@Serializable
enum class Mode(val title: String) { FLIRT("Флирт"), CHAT("Общение"), WORK("Работа") }

@Serializable
enum class Outfit(val title: String) { LINGERIE("Бельё"), CASUAL("Повседневное"), BUSINESS("Деловое") }

/** Эмоциональный настрой лица аватара. */
enum class Mood { SEDUCTIVE, TENDER, PLAYFUL, WARM, CALM, FOCUSED }

@Serializable
enum class ProviderType { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

@Serializable
enum class TtsEngine { SYSTEM, ENDPOINT }

@Serializable
data class ChatMessage(
    val id: String,
    val roleId: String,
    val fromUser: Boolean,
    val text: String,
    val ts: Long,
)

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val updatedAt: Long = 0,
)

/** Настройки, которые синхронизируются между устройствами (без секретов). */
@Serializable
data class Preferences(
    val activeProviderId: String = "openrouter",
    val mode: Mode = Mode.CHAT,
    val roleIdByMode: Map<String, String> = emptyMap(),
    val outfitOverride: Outfit? = null,
    val assistantName: String = "Ария",
    val userName: String = "",
    val temperature: Double = 0.85,
    val useTemperature: Boolean = true,
    val adultConfirmed: Boolean = false,
    val updatedAt: Long = 0,
)

/** Локальные настройки голоса (не синхронизируются: содержат ключ и зависят от устройства). */
@Serializable
data class VoiceConfig(
    val speakReplies: Boolean = true,
    val autoListen: Boolean = false,
    val language: String = "ru",
    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    val systemVoiceName: String = "",
    val endpointBaseUrl: String = "https://api.openai.com/v1",
    val endpointKey: String = "",
    val sttModel: String = "whisper-1",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "nova",
)

@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val webDavUrl: String = "",
    val user: String = "",
    val password: String = "",
)

@Serializable
data class AppData(
    val prefs: Preferences = Preferences(),
    val providers: List<ProviderConfig> = ProviderPresets.all(),
    val voice: VoiceConfig = VoiceConfig(),
    val sync: SyncConfig = SyncConfig(),
    val messages: List<ChatMessage> = emptyList(),
    val clearedAt: Map<String, Long> = emptyMap(),
)

/** То, что уходит в облако: без API-ключей и локальных настроек. */
@Serializable
data class SyncSnapshot(
    val version: Int = 1,
    val prefs: Preferences,
    val providers: List<ProviderConfig>,
    val messages: List<ChatMessage>,
    val clearedAt: Map<String, Long>,
)

val AppData.provider: ProviderConfig?
    get() = providers.firstOrNull { it.id == prefs.activeProviderId } ?: providers.firstOrNull()

val AppData.role: Role
    get() = Roles.byId(prefs.roleIdByMode[prefs.mode.name])?.takeIf { it.mode == prefs.mode }
        ?: Roles.default(prefs.mode)

val AppData.outfit: Outfit
    get() = prefs.outfitOverride ?: role.outfit

object ProviderPresets {
    fun all(): List<ProviderConfig> = listOf(
        ProviderConfig("openrouter", "OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", model = "openrouter/auto"),
        ProviderConfig("openai", "OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1"),
        ProviderConfig("anthropic", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com"),
        ProviderConfig("gemini", "Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com"),
        ProviderConfig("groq", "Groq", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1"),
        ProviderConfig("deepseek", "DeepSeek", ProviderType.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1"),
        ProviderConfig("mistral", "Mistral", ProviderType.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1"),
        ProviderConfig("ollama", "Ollama (локально)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:11434/v1"),
        ProviderConfig("lmstudio", "LM Studio (локально)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:1234/v1"),
    )
}
