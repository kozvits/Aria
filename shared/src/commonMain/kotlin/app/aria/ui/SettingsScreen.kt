package app.aria.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aria.AssistantController
import app.aria.Outfit
import app.aria.ProviderType
import app.aria.TtsEngine
import app.aria.UiState
import app.aria.provider
import app.aria.role

@Composable
fun SettingsScreen(ui: UiState, c: AssistantController, pal: Palette, onBack: () -> Unit) {
    val d = ui.data
    val p = d.provider
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад", color = pal.accent) }
                Text("Настройки", color = pal.text, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            }

            // ------------------------------------------------ провайдер
            Header("Провайдер ИИ", pal)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.providers) { pr -> Pill(pr.name, pr.id == p?.id, pal) { c.selectProvider(pr.id) } }
            }
            TextButton(onClick = c::addCustomProvider) { Text("+ Свой OpenAI-совместимый сервер", color = pal.accent) }
            if (p != null) {
                Field("Название", p.name, p.id) { v -> c.updateProvider(p.id) { it.copy(name = v) } }
                Field("Базовый URL", p.baseUrl, p.id) { v -> c.updateProvider(p.id) { it.copy(baseUrl = v) } }
                Field("API-ключ", p.apiKey, p.id, secret = true) { v -> c.updateProvider(p.id) { it.copy(apiKey = v) } }
                Field("Модель (можно вписать вручную)", p.model, p.id + "|" + p.model) { v ->
                    c.updateProvider(p.id) { it.copy(model = v) }
                }
                Button(onClick = c::loadModels, enabled = !ui.modelsLoading) {
                    Text(if (ui.modelsLoading) "Загрузка…" else "Загрузить список моделей")
                }
                if (ui.models.isNotEmpty()) {
                    ModelPicker(ui.models, p.model, pal) { m -> c.updateProvider(p.id) { it.copy(model = m) } }
                }
                Text(
                    when (p.type) {
                        ProviderType.OPENAI_COMPATIBLE -> "Протокол OpenAI Chat Completions. Подходит для OpenRouter, OpenAI, Groq, DeepSeek, Mistral, Ollama, LM Studio и любого совместимого сервера."
                        ProviderType.ANTHROPIC -> "Нативный протокол Anthropic Messages."
                        ProviderType.GEMINI -> "Нативный протокол Google Gemini (generativelanguage API)."
                    },
                    color = pal.muted, fontSize = 12.sp,
                )
            }

            // ------------------------------------------------ ассистентка
            Header("Ассистентка", pal)
            Field("Имя ассистентки", d.prefs.assistantName, "an") { v -> c.updatePrefs { it.copy(assistantName = v) } }
            Field("Как к вам обращаться", d.prefs.userName, "un") { v -> c.updatePrefs { it.copy(userName = v) } }
            SwitchRow("Передавать temperature (отключите для gpt-5 и o-серии)", d.prefs.useTemperature, pal) { v ->
                c.updatePrefs { it.copy(useTemperature = v) }
            }
            if (d.prefs.useTemperature) {
                Text("Живость ответов: ${((d.prefs.temperature * 100).toInt() / 100.0)}", color = pal.text, fontSize = 14.sp)
                Slider(
                    value = d.prefs.temperature.toFloat(),
                    onValueChange = { v -> c.updatePrefs { it.copy(temperature = v.toDouble()) } },
                    valueRange = 0f..1.2f,
                )
            }
            Text("Одежда (сейчас: ${d.role.outfit.title}${if (d.prefs.outfitOverride == null) ", по режиму" else ""})", color = pal.text, fontSize = 14.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("Авто", d.prefs.outfitOverride == null, pal) { c.updatePrefs { it.copy(outfitOverride = null) } } }
                items(Outfit.entries.toList()) { o ->
                    Pill(o.title, d.prefs.outfitOverride == o, pal) { c.updatePrefs { it.copy(outfitOverride = o) } }
                }
            }

            // ------------------------------------------------ голос
            Header("Голос", pal)
            SwitchRow("Озвучивать ответы", d.voice.speakReplies, pal) { v -> c.updateVoice { it.copy(speakReplies = v) } }
            SwitchRow("Слушать снова после ответа (hands-free)", d.voice.autoListen, pal) { v -> c.updateVoice { it.copy(autoListen = v) } }
            Field("Язык речи (ru, en, de…)", d.voice.language, "lang") { v -> c.updateVoice { it.copy(language = v) } }

            Text("Распознавание речи (Whisper API)", color = pal.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Field("URL сервера", d.voice.endpointBaseUrl, "evu") { v -> c.updateVoice { it.copy(endpointBaseUrl = v) } }
            Field("Ключ сервера", d.voice.endpointKey, "evk", secret = true) { v -> c.updateVoice { it.copy(endpointKey = v) } }
            Field("Модель распознавания", d.voice.sttModel, "stt") { v -> c.updateVoice { it.copy(sttModel = v) } }

            Text("Озвучка ответов", color = pal.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("Системный голос", d.voice.ttsEngine == TtsEngine.SYSTEM, pal) { c.updateVoice { it.copy(ttsEngine = TtsEngine.SYSTEM) } } }
                item { Pill("Сервер (/audio/speech)", d.voice.ttsEngine == TtsEngine.ENDPOINT, pal) { c.updateVoice { it.copy(ttsEngine = TtsEngine.ENDPOINT) } } }
            }
            if (d.voice.ttsEngine == TtsEngine.SYSTEM) {
                Field("Имя системного голоса (пусто — женский голос по языку)", d.voice.systemVoiceName, "svn") { v ->
                    c.updateVoice { it.copy(systemVoiceName = v) }
                }
                TextButton(onClick = c::listSystemVoices) { Text("Показать установленные голоса", color = pal.accent) }
                ui.systemVoices.forEach { v ->
                    Text(
                        v, color = pal.muted, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { c.updateVoice { it.copy(systemVoiceName = v) } }.padding(vertical = 4.dp),
                    )
                }
            } else {
                Field("Модель озвучки", d.voice.ttsModel, "ttm") { v -> c.updateVoice { it.copy(ttsModel = v) } }
                Field("Голос", d.voice.ttsVoice, "ttv") { v -> c.updateVoice { it.copy(ttsVoice = v) } }
                Text("Используются URL и ключ из блока распознавания выше.", color = pal.muted, fontSize = 12.sp)
            }

            // ------------------------------------------------ синхронизация
            Header("Синхронизация (Windows ↔ Android)", pal)
            SwitchRow("Включить синхронизацию", d.sync.enabled, pal) { v -> c.updateSync { it.copy(enabled = v) } }
            Field("Адрес папки WebDAV", d.sync.webDavUrl, "dav") { v -> c.updateSync { it.copy(webDavUrl = v) } }
            Field("Логин", d.sync.user, "dvu") { v -> c.updateSync { it.copy(user = v) } }
            Field("Пароль (для Яндекс.Диска — пароль приложения)", d.sync.password, "dvp", secret = true) { v -> c.updateSync { it.copy(password = v) } }
            Button(onClick = c::syncNow) { Text("Синхронизировать сейчас") }
            if (ui.syncStatus.isNotEmpty()) Text(ui.syncStatus, color = pal.muted, fontSize = 13.sp)
            Text(
                "Синхронизируются диалоги, роли, имя, выбор провайдера и модели. API-ключи остаются на устройстве и на втором нужно ввести их заново. Пример для Яндекс.Диска: https://webdav.yandex.ru/Aria/",
                color = pal.muted, fontSize = 12.sp,
            )

            // ------------------------------------------------ данные
            Header("Данные", pal)
            Button(onClick = c::clearHistory) { Text("Очистить историю роли «${d.role.title}»") }
            if (ui.status.isNotEmpty()) Text(ui.status, color = pal.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Header(text: String, pal: Palette) {
    Text(
        text, color = pal.accent, fontSize = 18.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp),
    )
}

/** Локальное состояние поля: ввод не «прыгает» из-за асинхронного обновления общего состояния. */
@Composable
private fun Field(label: String, value: String, key: Any, secret: Boolean = false, onChange: (String) -> Unit) {
    var v by remember(key) { mutableStateOf(value) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, pal: Palette, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelPicker(models: List<String>, current: String, pal: Palette, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(models, q) { models.filter { it.contains(q, ignoreCase = true) }.take(200) }
    OutlinedTextField(
        value = q, onValueChange = { q = it },
        label = { Text("Поиск по ${models.size} моделям") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
        items(filtered) { m ->
            Text(
                m,
                color = if (m == current) pal.accent else pal.text,
                fontWeight = if (m == current) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}
