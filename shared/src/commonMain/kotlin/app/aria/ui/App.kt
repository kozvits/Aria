package app.aria.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aria.AriaApp
import app.aria.AssistantController
import app.aria.Mode
import app.aria.Roles
import app.aria.UiState
import app.aria.outfit
import app.aria.role

@Composable
fun App() {
    val c = remember { AriaApp.controller }
    val ui by c.state.collectAsState()
    val pal = paletteFor(ui.data.prefs.mode)
    val top by animateColorAsState(pal.top, tween(700))
    val bottom by animateColorAsState(pal.bottom, tween(700))
    var settings by remember { mutableStateOf(false) }

    MaterialTheme(
        colors = darkColors(
            primary = pal.accent,
            onPrimary = Color(0xFF1A0A10),
            background = bottom,
            surface = pal.panel,
            onBackground = pal.text,
            onSurface = pal.text,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
            if (settings) SettingsScreen(ui, c, pal) { settings = false }
            else MainScreen(ui, c, pal) { settings = true }
            if (ui.showAgeGate) AgeGate(c)
        }
    }
}

@Composable
private fun MainScreen(ui: UiState, c: AssistantController, pal: Palette, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 820.dp) {
            Row(Modifier.fillMaxSize()) {
                Stage(ui, pal, Modifier.weight(0.42f).fillMaxHeight())
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    TopBar(ui, c, pal, onSettings)
                    Chat(ui, pal, Modifier.weight(1f).fillMaxWidth())
                    InputBar(ui, c, pal)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(ui, c, pal, onSettings)
                val stageH = 340.dp
                Stage(ui, pal, Modifier.fillMaxWidth().height(stageH))
                Chat(ui, pal, Modifier.weight(1f).fillMaxWidth())
                InputBar(ui, c, pal)
            }
        }
    }
}

@Composable
private fun TopBar(ui: UiState, c: AssistantController, pal: Palette, onSettings: () -> Unit) {
    val d = ui.data
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mode.entries.forEach { m ->
                Pill(m.title, m == d.prefs.mode, pal, Modifier.padding(end = 8.dp)) { c.requestMode(m) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "⚙", color = pal.muted, fontSize = 24.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onSettings).padding(8.dp),
            )
        }
        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Roles.forMode(d.prefs.mode)) { r ->
                Pill(r.title, r.id == d.role.id, pal) { c.setRole(r.id) }
            }
        }
    }
}

@Composable
private fun Stage(ui: UiState, pal: Palette, modifier: Modifier) {
    val d = ui.data
    val role = d.role
    val thinking = ui.busy && ui.streaming.isEmpty()
    val line = when {
        ui.listening -> "Слушаю…"
        thinking -> "Думаю…"
        ui.speaking -> "Говорит…"
        else -> role.subtitle
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(pal.accent.copy(alpha = 0.26f), Color.Transparent)),
            ),
        )
        Avatar(
            mood = role.mood,
            outfit = d.outfit,
            speaking = ui.speaking,
            listening = ui.listening,
            thinking = thinking,
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(d.prefs.assistantName, color = pal.text, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            Text("${role.title} · $line", color = pal.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Chat(ui: UiState, pal: Palette, modifier: Modifier) {
    val roleId = ui.data.role.id
    val msgs = remember(ui.data.messages, roleId) { ui.data.messages.filter { it.roleId == roleId }.takeLast(200) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, ui.streaming.length / 40, ui.busy) {
        val n = list.layoutInfo.totalItemsCount
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    LazyColumn(
        modifier.padding(horizontal = 12.dp),
        state = list,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (msgs.isEmpty() && ui.streaming.isEmpty() && !ui.busy) {
            item { Text("Напишите сообщение или нажмите на микрофон.", color = pal.muted, fontSize = 14.sp) }
        }
        items(msgs, key = { it.id }) { Bubble(it.text, it.fromUser, pal) }
        if (ui.streaming.isNotEmpty()) item { Bubble(ui.streaming, false, pal) }
        else if (ui.busy) item { Text("…", color = pal.muted, fontSize = 18.sp) }
    }
}

@Composable
private fun Bubble(text: String, user: Boolean, pal: Palette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (user) 18.dp else 4.dp, bottomEnd = if (user) 4.dp else 18.dp,
                    ),
                )
                .background(if (user) pal.userBubble else pal.botBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text, color = pal.text, fontSize = 16.sp, lineHeight = 23.sp,
                fontFamily = if (user) FontFamily.Default else FontFamily.Serif,
            )
        }
    }
}

@Composable
private fun InputBar(ui: UiState, c: AssistantController, pal: Palette) {
    var text by remember { mutableStateOf("") }
    fun send() {
        val t = text
        if (t.isNotBlank()) {
            text = ""
            c.send(t)
        }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        if (ui.status.isNotEmpty()) {
            Text(ui.status, color = pal.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                        send(); true
                    } else false
                },
                placeholder = { Text("Напишите или нажмите микрофон", color = pal.muted) },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            MicButton(ui, pal) { c.toggleMic() }
            Spacer(Modifier.width(8.dp))
            if (ui.busy || ui.speaking || ui.listening) RoundButton("■", pal) { c.stopAll() }
            else RoundButton("↑", pal) { send() }
        }
    }
}

@Composable
private fun MicButton(ui: UiState, pal: Palette, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (ui.listening) 1f + ui.level * 0.55f else 1f, tween(90))
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (ui.listening) {
            Box(
                Modifier.size(56.dp)
                    .graphicsLayer { scaleX = ring; scaleY = ring }
                    .clip(CircleShape)
                    .background(pal.accent.copy(alpha = 0.28f)),
            )
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (ui.listening) pal.accent else Color.White.copy(alpha = 0.10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            MicIcon(if (ui.listening) Color(0xFF1A0A10) else pal.text)
        }
    }
}

@Composable
private fun RoundButton(symbol: String, pal: Palette, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(pal.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color(0xFF1A0A10), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AgeGate(c: AssistantController) {
    AlertDialog(
        onDismissRequest = c::dismissAgeGate,
        title = { Text("Режим 18+") },
        text = {
            Text(
                "В режиме «Флирт» ассистентка играет роль, говорит с намёками и выглядит соблазнительно. " +
                    "Это вымышленный персонаж на базе ИИ. Подтвердите, что вам есть 18 лет."
            )
        },
        confirmButton = { TextButton(onClick = c::confirmAdult) { Text("Мне есть 18") } },
        dismissButton = { TextButton(onClick = c::dismissAgeGate) { Text("Отмена") } },
    )
}
