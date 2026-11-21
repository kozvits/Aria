package app.aria

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val data: AppData,
    val busy: Boolean = false,
    val streaming: String = "",
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val level: Float = 0f,
    val status: String = "",
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val syncStatus: String = "",
    val showAgeGate: Boolean = false,
    val systemVoices: List<String> = emptyList(),
)

/** Один экземпляр на процесс: переживает пересоздание Activity на Android. */
object AriaApp {
    val controller: AssistantController by lazy { AssistantController() }
}

private const val SPEECH_LEVEL = 0.15f // порог «человек говорит»; поднимите, если в комнате шумно

class AssistantController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = Store()
    private val voice = VoiceService()

    private val _state = MutableStateFlow(UiState(store.load()))
    val state: StateFlow<UiState> = _state

    private var saveJob: Job? = null
    private var genJob: Job? = null
    private var listenJob: Job? = null
    private var syncJob: Job? = null
    private var speakerJob: Job? = null
    private var queue = Channel<String>(Channel.UNLIMITED)
    private val pending = MutableStateFlow(0)
    private val stopListenFlag = MutableStateFlow(false)

    init {
        startSpeaker()
        scope.launch { pending.collect { n -> upd { copy(speaking = n > 0) } } }
        if (_state.value.data.sync.enabled) runSync()
    }

    // ------------------------------------------------------------ состояние
    private fun upd(f: UiState.() -> UiState) = _state.update { it.f() }

    private fun edit(f: (AppData) -> AppData) {
        _state.update { it.copy(data = f(it.data)) }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            store.save(_state.value.data)
        }
    }

    private fun editPrefs(f: (Preferences) -> Preferences) =
        edit { it.copy(prefs = f(it.prefs).copy(updatedAt = nowMillis())) }

    fun updatePrefs(f: (Preferences) -> Preferences) = editPrefs(f)
    fun updateVoice(f: (VoiceConfig) -> VoiceConfig) = edit { it.copy(voice = f(it.voice)) }
    fun updateSync(f: (SyncConfig) -> SyncConfig) = edit { it.copy(sync = f(it.sync)) }

    // ------------------------------------------------------------ режимы и роли
    fun requestMode(m: Mode) {
        val d = _state.value.data
        if (m == d.prefs.mode) return
        if (m == Mode.FLIRT && !d.prefs.adultConfirmed) {
            upd { copy(showAgeGate = true) }
            return
        }
        applyMode(m)
    }

    fun confirmAdult() {
        editPrefs { it.copy(adultConfirmed = true) }
        upd { copy(showAgeGate = false) }
        applyMode(Mode.FLIRT)
    }

    fun dismissAgeGate() = upd { copy(showAgeGate = false) }

    private fun applyMode(m: Mode) {
        stopAll()
        editPrefs { it.copy(mode = m) }
    }

    fun setRole(id: String) {
        stopAll()
        editPrefs { it.copy(roleIdByMode = it.roleIdByMode + (it.mode.name to id)) }
    }

    fun clearHistory() {
        val id = _state.value.data.role.id
        edit { d ->
            d.copy(
                messages = d.messages.filterNot { it.roleId == id },
                clearedAt = d.clearedAt + (id to nowMillis()),
            )
        }
    }

    // ------------------------------------------------------------ провайдеры и модели
    fun selectProvider(id: String) {
        editPrefs { it.copy(activeProviderId = id) }
        upd { copy(models = emptyList(), status = "") }
    }

    fun updateProvider(id: String, f: (ProviderConfig) -> ProviderConfig) = edit { d ->
        d.copy(providers = d.providers.map { if (it.id == id) f(it).copy(updatedAt = nowMillis()) else it })
    }

    fun addCustomProvider() {
        val p = ProviderConfig(
            id = "custom-" + newId(),
            name = "Свой сервер",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://",
            updatedAt = nowMillis(),
        )
        edit { it.copy(providers = it.providers + p) }
        selectProvider(p.id)
    }

    fun loadModels() {
        val p = _state.value.data.provider ?: return
        upd { copy(modelsLoading = true, status = "") }
        scope.launch {
            try {
                val m = createLlm(p).listModels()
                upd { copy(models = m, modelsLoading = false, status = if (m.isEmpty()) "Сервер вернул пустой список моделей" else "") }
            } catch (e: Throwable) {
                upd { copy(modelsLoading = false, status = "Список моделей: ${e.message}") }
            }
        }
    }

    fun listSystemVoices() {
        scope.launch {
            val v = runCatching { voice.systemVoices() }.getOrDefault(emptyList())
            upd { copy(systemVoices = v, status = if (v.isEmpty()) "Системные голоса не найдены" else "") }
        }
    }

    // ------------------------------------------------------------ диалог
    fun send(text: String) {
        val t = text.trim()
        val s0 = _state.value
        if (t.isEmpty() || s0.busy) return
        val d0 = s0.data
        val provider = d0.provider
        if (provider == null || provider.model.isBlank()) {
            upd { copy(status = "Выберите провайдера и модель в настройках (значок ⚙)") }
            return
        }
        stopSpeech()
        val role = d0.role
        val userMsg = ChatMessage(newId(), role.id, true, t, nowMillis())
        edit { it.copy(messages = it.messages + userMsg) }
        val history = _state.value.data.messages
            .filter { it.roleId == role.id }
            .takeLast(40)
            .map { Turn(it.fromUser, it.text) }
        val system = Roles.systemPrompt(role, d0.prefs.assistantName, d0.prefs.userName)
        val temperature = if (d0.prefs.useTemperature) d0.prefs.temperature else null
        val speak = d0.voice.speakReplies
        upd { copy(busy = true, streaming = "", status = "") }

        genJob = scope.launch {
            val full = StringBuilder()
            val chunker = SentenceChunker()
            var error: String? = null
            try {
                createLlm(provider).stream(system, history, temperature, provider.model).collect { delta ->
                    full.append(delta)
                    val snapshot = full.toString()
                    upd { copy(streaming = snapshot) }
                    if (speak) chunker.feed(delta).forEach { enqueueSpeech(it) }
                }
                if (speak) chunker.flush().forEach { enqueueSpeech(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                val reply = full.toString().trim()
                if (reply.isNotEmpty()) {
                    edit { it.copy(messages = it.messages + ChatMessage(newId(), role.id, false, reply, nowMillis())) }
                } else if (error == null) {
                    error = "модель вернула пустой ответ"
                }
                val err = error
                upd { copy(busy = false, streaming = "", status = if (err != null) "Ошибка: $err" else status) }
            }
            maybeAutoListen()
            if (_state.value.data.sync.enabled) runSync()
        }
    }

    fun stopAll() {
        genJob?.cancel()
        listenJob?.cancel()
        stopListenFlag.value = true
        stopSpeech()
    }

    // ------------------------------------------------------------ озвучка
    private fun startSpeaker() {
        val q = queue
        speakerJob = scope.launch {
            for (s in q) {
                val d = _state.value.data
                try {
                    voice.speak(s, d.voice, d.role)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    upd { copy(status = "Голос: ${e.message}") }
                } finally {
                    pending.update { (it - 1).coerceAtLeast(0) }
                }
                maybeAutoListen()
            }
        }
    }

    private fun enqueueSpeech(raw: String) {
        val t = SpeechText.clean(raw)
        if (t.isBlank()) return
        pending.update { it + 1 }
        queue.trySend(t)
    }

    fun stopSpeech() {
        voice.stopSpeech()
        speakerJob?.cancel()
        queue.close()
        queue = Channel(Channel.UNLIMITED)
        pending.value = 0
        startSpeaker()
    }

    // ------------------------------------------------------------ микрофон
    fun toggleMic() {
        if (_state.value.listening) stopListenFlag.value = true else startListening()
    }

    private fun maybeAutoListen() {
        val s = _state.value
        if (s.data.voice.autoListen && !s.busy && !s.listening && pending.value == 0) startListening()
    }

    fun startListening() {
        val s = _state.value
        if (s.listening || s.busy) return
        stopSpeech()
        stopListenFlag.value = false
        listenJob = scope.launch {
            try {
                voice.recorder.start()
                upd { copy(listening = true, status = "Слушаю…") }
                var heard = false
                var silent = 0
                var elapsed = 0
                while (elapsed < 45_000 && !stopListenFlag.value) {
                    delay(100)
                    elapsed += 100
                    val lv = voice.recorder.level.value
                    upd { copy(level = lv) }
                    if (lv > SPEECH_LEVEL) {
                        heard = true
                        silent = 0
                    } else if (heard) silent += 100
                    if (heard && silent >= 1300) break
                    if (!heard && elapsed >= 9000) break
                }
                val wav = voice.recorder.stop()
                upd { copy(listening = false, level = 0f) }
                if (!heard && !stopListenFlag.value) {
                    upd { copy(status = "") }
                    return@launch
                }
                upd { copy(status = "Распознаю…") }
                val text = voice.transcribe(wav, _state.value.data.voice)
                if (text.isBlank()) {
                    upd { copy(status = "Не расслышала, повторите") }
                } else {
                    upd { copy(status = "") }
                    send(text)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { voice.recorder.stop() } }
                throw e
            } catch (e: Throwable) {
                upd { copy(status = "Микрофон: ${e.message}") }
            } finally {
                upd { copy(listening = false, level = 0f) }
            }
        }
    }

    // ------------------------------------------------------------ синхронизация
    fun syncNow() = runSync()

    private fun runSync() {
        val cfg = _state.value.data.sync
        if (cfg.webDavUrl.isBlank()) {
            upd { copy(syncStatus = "Укажите адрес WebDAV") }
            return
        }
        syncJob?.cancel()
        syncJob = scope.launch {
            upd { copy(syncStatus = "Синхронизация…") }
            try {
                val dav = WebDavSync(cfg)
                dav.ensureFolder()
                val remote = dav.pull()
                if (remote != null) edit { SyncMerge.merge(it, remote) }
                dav.push(SyncMerge.snapshot(_state.value.data))
                upd { copy(syncStatus = "Синхронизировано") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                upd { copy(syncStatus = "Ошибка синхронизации: ${e.message}") }
            }
        }
    }
}
