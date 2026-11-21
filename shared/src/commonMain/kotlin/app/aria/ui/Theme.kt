package app.aria.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aria.Mode

data class Palette(
    val top: Color,
    val bottom: Color,
    val accent: Color,
    val panel: Color,
    val text: Color,
    val muted: Color,
    val userBubble: Color,
    val botBubble: Color,
)

/** Каждый режим — своя атмосфера: вино и золото, глубокая бирюза с янтарём, холодная сталь. */
fun paletteFor(mode: Mode): Palette = when (mode) {
    Mode.FLIRT -> Palette(
        top = Color(0xFF2E0A24), bottom = Color(0xFF12060F), accent = Color(0xFFFF7A93),
        panel = Color(0xFF1F0B1B), text = Color(0xFFFFEEF1), muted = Color(0xFFC79AA8),
        userBubble = Color(0xFF5A1A3B), botBubble = Color(0xFF2E1228),
    )
    Mode.CHAT -> Palette(
        top = Color(0xFF133A40), bottom = Color(0xFF0A1B1F), accent = Color(0xFFF0B866),
        panel = Color(0xFF0F2529), text = Color(0xFFEAF6F4), muted = Color(0xFF8EB5B2),
        userBubble = Color(0xFF2B5A57), botBubble = Color(0xFF173C40),
    )
    Mode.WORK -> Palette(
        top = Color(0xFF1B2636), bottom = Color(0xFF0D131B), accent = Color(0xFF7FB2F0),
        panel = Color(0xFF131B26), text = Color(0xFFE9EEF5), muted = Color(0xFF8798AE),
        userBubble = Color(0xFF2B4466), botBubble = Color(0xFF1B2735),
    )
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    pal: Palette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) pal.accent else Color.White.copy(alpha = 0.07f)
    val fg = if (selected) Color(0xFF1A0A10) else pal.text
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun MicIcon(color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.07f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.06f),
            size = Size(w * 0.28f, h * 0.5f),
            cornerRadius = CornerRadius(w * 0.14f),
        )
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.28f), size = Size(w * 0.56f, h * 0.46f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.35f, h * 0.92f), Offset(w * 0.65f, h * 0.92f), sw, StrokeCap.Round)
    }
}
