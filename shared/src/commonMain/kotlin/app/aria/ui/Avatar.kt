package app.aria.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import app.aria.Mood
import app.aria.Outfit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/*
 * Векторная ассистентка: слои рисуются на Canvas, поэтому одинаково работает на Windows и Android.
 * Это рабочая заглушка с анимацией (дыхание, моргание, мимика по настроению, движение губ при речи,
 * плавная смена одежды). Если захотите более «живую» графику — заменить нужно только этот файл
 * (Live2D / Rive / Lottie / набор PNG-слоёв): сигнатура Avatar(...) остаётся прежней.
 */

private const val VW = 1000f
private const val VH = 1180f

private val Skin = Color(0xFFEBBE9F)
private val SkinLight = Color(0xFFF4CDB2)
private val SkinShade = Color(0xFFD59F80)
private val HairDark = Color(0xFF2B1912)
private val HairLight = Color(0xFF5C3A27)
private val Lash = Color(0xFF1B120E)
private val Iris = Color(0xFF68703F)

private data class Face(
    val eyeOpen: Float, val smile: Float, val smirk: Float,
    val browL: Float, val browR: Float, val browInner: Float,
    val blush: Float, val shadow: Float, val lip: Color,
)

private fun faceFor(m: Mood) = when (m) {
    Mood.SEDUCTIVE -> Face(0.62f, 0.55f, 5f, 0f, -14f, 0f, 0.38f, 0.5f, Color(0xFFA81C3E))
    Mood.TENDER -> Face(0.72f, 0.85f, 0f, -6f, -6f, -2f, 0.45f, 0.3f, Color(0xFFC25468))
    Mood.PLAYFUL -> Face(0.90f, 1.00f, 4f, -8f, -12f, -2f, 0.28f, 0.3f, Color(0xFFCF4E68))
    Mood.WARM -> Face(0.90f, 0.90f, 0f, -4f, -4f, -2f, 0.20f, 0.18f, Color(0xFFBF5A62))
    Mood.CALM -> Face(0.82f, 0.45f, 0f, -2f, -2f, 0f, 0.12f, 0.15f, Color(0xFFB4636A))
    Mood.FOCUSED -> Face(0.88f, 0.12f, 0f, 2f, 2f, 5f, 0.06f, 0.10f, Color(0xFFA95A5F))
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpFace(a: Face, b: Face, t: Float) = Face(
    mix(a.eyeOpen, b.eyeOpen, t), mix(a.smile, b.smile, t), mix(a.smirk, b.smirk, t),
    mix(a.browL, b.browL, t), mix(a.browR, b.browR, t), mix(a.browInner, b.browInner, t),
    mix(a.blush, b.blush, t), mix(a.shadow, b.shadow, t), lerp(a.lip, b.lip, t),
)

@Composable
fun Avatar(
    mood: Mood,
    outfit: Outfit,
    speaking: Boolean,
    listening: Boolean,
    thinking: Boolean,
    modifier: Modifier = Modifier,
) {
    // плавный переход между выражениями лица
    var from by remember { mutableStateOf(faceFor(mood)) }
    var to by remember { mutableStateOf(faceFor(mood)) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        from = lerpFace(from, to, morph.value)
        to = faceFor(mood)
        morph.snapTo(0f)
        morph.animateTo(1f, tween(650))
    }

    val idle = rememberInfiniteTransition(label = "idle")
    val breath by idle.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    val sway by idle.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway",
    )

    var blink by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(Random.nextLong(2200, 5600))
            animate(0f, 1f, animationSpec = tween(70)) { v, _ -> blink = v }
            animate(1f, 0f, animationSpec = tween(120)) { v, _ -> blink = v }
            if (Random.nextFloat() < 0.15f) {
                delay(140)
                animate(0f, 1f, animationSpec = tween(70)) { v, _ -> blink = v }
                animate(1f, 0f, animationSpec = tween(120)) { v, _ -> blink = v }
            }
        }
    }

    // движение губ при речи
    var mouth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(speaking) {
        if (speaking) {
            while (isActive) {
                val target = if (Random.nextFloat() < 0.18f) 0.05f else 0.25f + Random.nextFloat() * 0.65f
                animate(mouth, target, animationSpec = tween(Random.nextInt(70, 150))) { v, _ -> mouth = v }
            }
        } else {
            animate(mouth, 0f, animationSpec = tween(160)) { v, _ -> mouth = v }
        }
    }

    val tilt by animateFloatAsState(if (listening) 2.4f else 0f, tween(500), label = "tilt")
    val wide by animateFloatAsState(if (listening) 0.12f else 0f, tween(300), label = "wide")
    val gazeX by animateFloatAsState(if (thinking) 9f else 0f, tween(450), label = "gx")
    val gazeY by animateFloatAsState(if (thinking) -7f else 0f, tween(450), label = "gy")

    val aLin by animateFloatAsState(if (outfit == Outfit.LINGERIE) 1f else 0f, tween(800), label = "lin")
    val aCas by animateFloatAsState(if (outfit == Outfit.CASUAL) 1f else 0f, tween(800), label = "cas")
    val aBus by animateFloatAsState(if (outfit == Outfit.BUSINESS) 1f else 0f, tween(800), label = "bus")

    Box(
        modifier.graphicsLayer {
            rotationZ = sway * 0.9f + tilt
            translationY = breath * 7f
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    ) {
        Canvas(Modifier.fillMaxSize()) { stage { hairBack(); body() } }
        if (aLin > 0.01f) Canvas(Modifier.fillMaxSize()) { stage { lingerie(aLin) } }
        if (aCas > 0.01f) Canvas(Modifier.fillMaxSize()) { stage { casual(aCas) } }
        if (aBus > 0.01f) Canvas(Modifier.fillMaxSize()) { stage { business(aBus) } }
        Canvas(Modifier.fillMaxSize()) {
            stage {
                val f = lerpFace(from, to, morph.value)
                hairLocks()
                face(f, blink, mouth, wide, gazeX, gazeY)
                bangs()
            }
        }
    }
}

private fun DrawScope.stage(block: DrawScope.() -> Unit) {
    val s = minOf(size.width / VW, size.height / VH)
    val dx = (size.width - VW * s) / 2f
    val dy = size.height - VH * s
    withTransform({
        translate(dx, dy)
        scale(s, s, pivot = Offset.Zero)
    }) { block() }
}

// ------------------------------------------------------------------ волосы и тело

private fun DrawScope.hairBack() {
    val p = Path().apply {
        moveTo(500f, 185f)
        cubicTo(295f, 185f, 232f, 360f, 238f, 560f)
        cubicTo(244f, 760f, 188f, 880f, 214f, 1030f)
        lineTo(786f, 1030f)
        cubicTo(812f, 880f, 756f, 760f, 762f, 560f)
        cubicTo(768f, 360f, 705f, 185f, 500f, 185f)
        close()
    }
    drawPath(p, Brush.verticalGradient(listOf(HairLight, HairDark), startY = 185f, endY = 1030f))
}

private fun DrawScope.body() {
    val shoulders = Path().apply {
        moveTo(90f, 1300f); lineTo(90f, 1010f)
        cubicTo(90f, 880f, 250f, 800f, 430f, 745f)
        lineTo(570f, 745f)
        cubicTo(750f, 800f, 910f, 880f, 910f, 1010f)
        lineTo(910f, 1300f); close()
    }
    drawPath(shoulders, Skin)
    val neck = Path().apply {
        moveTo(432f, 540f); lineTo(425f, 770f)
        quadraticTo(500f, 835f, 575f, 770f)
        lineTo(568f, 540f); close()
    }
    drawPath(neck, Brush.verticalGradient(listOf(SkinShade, Skin), startY = 560f, endY = 800f))
    for (sgn in listOf(-1f, 1f)) {
        val bone = Path().apply {
            moveTo(500f + sgn * 170f, 835f)
            quadraticTo(500f + sgn * 90f, 858f, 500f + sgn * 20f, 842f)
        }
        drawPath(bone, SkinShade, alpha = 0.5f, style = Stroke(5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.hairLocks() {
    val left = Path().apply {
        moveTo(345f, 470f)
        cubicTo(300f, 560f, 262f, 700f, 272f, 820f)
        cubicTo(280f, 920f, 250f, 960f, 235f, 1010f)
        cubicTo(300f, 1040f, 380f, 1000f, 392f, 930f)
        cubicTo(390f, 800f, 372f, 640f, 372f, 520f)
        close()
    }
    val brush = Brush.verticalGradient(listOf(HairLight, HairDark), startY = 470f, endY = 1010f)
    drawPath(left, brush)
    scale(-1f, 1f, pivot = Offset(500f, 0f)) { drawPath(left, brush) }
}

// ------------------------------------------------------------------ одежда

private fun DrawScope.lingerie(alpha: Float) {
    val satin = Color(0xFF1C1020)
    val lace = Color(0xFFEBB9C8)
    val neckline = Path().apply {
        moveTo(225f, 1030f)
        quadraticTo(330f, 985f, 415f, 1010f)
        quadraticTo(500f, 1090f, 585f, 1010f)
        quadraticTo(670f, 985f, 775f, 1030f)
    }
    val cami = Path().apply {
        moveTo(210f, 1300f); lineTo(225f, 1030f)
        quadraticTo(330f, 985f, 415f, 1010f)
        quadraticTo(500f, 1090f, 585f, 1010f)
        quadraticTo(670f, 985f, 775f, 1030f)
        lineTo(790f, 1300f); close()
    }
    drawPath(cami, Brush.verticalGradient(listOf(Color(0xFF42203A), satin), startY = 1000f, endY = 1300f), alpha = alpha)
    drawLine(satin, Offset(405f, 1012f), Offset(395f, 790f), 12f, StrokeCap.Round, alpha = alpha)
    drawLine(satin, Offset(595f, 1012f), Offset(605f, 790f), 12f, StrokeCap.Round, alpha = alpha)
    drawPath(neckline, lace, alpha = alpha, style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    translate(0f, 14f) {
        drawPath(
            neckline, lace, alpha = alpha * 0.9f,
            style = Stroke(9f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 17f))),
        )
    }
    // блик на атласе
    drawPath(
        Path().apply { moveTo(300f, 1120f); quadraticTo(330f, 1200f, 320f, 1290f) },
        Color.White, alpha = alpha * 0.08f, style = Stroke(16f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.casual(alpha: Float) {
    val knit = Color(0xFFC9AB80)
    val rib = Color(0xFFAF8C60)
    val body = Path().apply {
        moveTo(70f, 1300f); lineTo(70f, 1030f)
        cubicTo(70f, 890f, 250f, 810f, 420f, 752f)
        lineTo(580f, 752f)
        cubicTo(750f, 810f, 930f, 890f, 930f, 1030f)
        lineTo(930f, 1300f); close()
    }
    drawPath(body, knit, alpha = alpha)
    for (i in 0..11) {
        drawLine(rib, Offset(110f + i * 70f, 1000f), Offset(110f + i * 70f, 1300f), 3f, alpha = alpha * 0.22f)
    }
    val scoop = Path().apply {
        moveTo(418f, 752f)
        quadraticTo(500f, 935f, 582f, 752f)
        close()
    }
    drawPath(scoop, Skin, alpha = alpha)
    val edge = Path().apply {
        moveTo(418f, 752f)
        quadraticTo(500f, 935f, 582f, 752f)
    }
    drawPath(edge, rib, alpha = alpha, style = Stroke(16f, cap = StrokeCap.Round))
}

private fun DrawScope.business(alpha: Float) {
    val navy = Color(0xFF1E2A44)
    val navyLight = Color(0xFF2C3D63)
    val blouse = Color(0xFFF4F1EC)
    val gold = Color(0xFFD9B36A)
    val body = Path().apply {
        moveTo(80f, 1300f); lineTo(80f, 1015f)
        cubicTo(80f, 885f, 245f, 805f, 425f, 748f)
        lineTo(575f, 748f)
        cubicTo(755f, 805f, 920f, 885f, 920f, 1015f)
        lineTo(920f, 1300f); close()
    }
    drawPath(body, navy, alpha = alpha)
    drawPath(Path().apply { moveTo(425f, 748f); lineTo(575f, 748f); lineTo(500f, 1110f); close() }, blouse, alpha = alpha)
    drawPath(Path().apply { moveTo(446f, 752f); lineTo(554f, 752f); lineTo(500f, 925f); close() }, Skin, alpha = alpha)
    drawPath(
        Path().apply { moveTo(425f, 748f); lineTo(500f, 1110f); lineTo(372f, 1040f); lineTo(318f, 850f); close() },
        navyLight, alpha = alpha,
    )
    drawPath(
        Path().apply { moveTo(575f, 748f); lineTo(500f, 1110f); lineTo(628f, 1040f); lineTo(682f, 850f); close() },
        navyLight, alpha = alpha,
    )
    drawCircle(gold, 7f, Offset(500f, 1190f), alpha = alpha)
    drawCircle(gold, 7f, Offset(500f, 1250f), alpha = alpha)
    // тонкая цепочка с подвеской
    val chain = Path().apply { moveTo(456f, 772f); quadraticTo(500f, 872f, 544f, 772f) }
    drawPath(chain, gold, alpha = alpha, style = Stroke(3.5f, cap = StrokeCap.Round))
    drawCircle(gold, 9f, Offset(500f, 838f), alpha = alpha)
}

// ------------------------------------------------------------------ лицо

private fun DrawScope.face(f: Face, blink: Float, mouthOpen: Float, wide: Float, gx: Float, gy: Float) {
    // уши и серьги
    drawOval(Skin, Offset(340f, 425f), Size(46f, 84f))
    drawOval(Skin, Offset(614f, 425f), Size(46f, 84f))
    drawCircle(Color(0xFFF6F0EA), 9f, Offset(362f, 520f))
    drawCircle(Color(0xFFF6F0EA), 9f, Offset(638f, 520f))

    val head = Path().apply {
        moveTo(500f, 235f)
        cubicTo(655f, 235f, 668f, 420f, 640f, 515f)
        cubicTo(615f, 605f, 560f, 655f, 500f, 655f)
        cubicTo(440f, 655f, 385f, 605f, 360f, 515f)
        cubicTo(332f, 420f, 345f, 235f, 500f, 235f)
        close()
    }
    drawPath(head, Brush.radialGradient(listOf(SkinLight, Skin), center = Offset(500f, 430f), radius = 280f))

    // румянец
    val blush = Color(0xFFE57C84)
    for (x in listOf(425f, 575f)) {
        drawCircle(
            Brush.radialGradient(listOf(blush.copy(alpha = f.blush), Color.Transparent), center = Offset(x, 520f), radius = 62f),
            radius = 62f, center = Offset(x, 520f),
        )
    }

    // возраст: мягкие носогубные линии и «гусиные лапки», едва заметные
    for (sgn in listOf(-1f, 1f)) {
        val fold = Path().apply {
            moveTo(500f + sgn * 72f, 520f)
            quadraticTo(500f + sgn * 92f, 560f, 500f + sgn * 74f, 602f)
        }
        drawPath(fold, SkinShade, alpha = 0.30f, style = Stroke(4f, cap = StrokeCap.Round))
        for (k in -1..1) {
            drawLine(
                SkinShade, Offset(500f + sgn * 108f, 430f + k * 10f), Offset(500f + sgn * 124f, 430f + k * 18f),
                3f, StrokeCap.Round, alpha = 0.25f,
            )
        }
    }

    val open = (f.eyeOpen + wide).coerceAtMost(1f)
    brows(f)
    eye(430f, -1f, f, open, blink, gx, gy)
    eye(570f, 1f, f, open, blink, gx, gy)

    // нос
    val nose = Path().apply {
        moveTo(497f, 432f)
        quadraticTo(485f, 500f, 478f, 520f)
        quadraticTo(500f, 538f, 522f, 520f)
    }
    drawPath(nose, SkinShade, alpha = 0.55f, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    mouth(f, mouthOpen)
}

private fun DrawScope.brows(f: Face) {
    fun brow(outerX: Float, innerX: Float, y: Float) {
        val p = Path().apply {
            moveTo(outerX, y + 10f)
            quadraticTo((outerX + innerX) / 2f, y - 26f, innerX, y + 4f + f.browInner)
        }
        drawPath(p, HairDark, alpha = 0.92f, style = Stroke(9f, cap = StrokeCap.Round))
    }
    brow(380f, 484f, 372f + f.browL)
    brow(620f, 516f, 372f + f.browR)
}

private fun DrawScope.eye(cx: Float, outer: Float, f: Face, openBase: Float, blink: Float, gx: Float, gy: Float) {
    val cy = 430f
    val w = 70f
    val open = (openBase * (1f - blink)).coerceIn(0.05f, 1f)
    val up = 30f * open
    val down = 14f * open
    val x0 = cx - w / 2f
    val x1 = cx + w / 2f

    drawOval(
        Color(0xFF8A4A5C).copy(alpha = 0.10f + 0.30f * f.shadow),
        topLeft = Offset(cx - w * 0.62f, cy - up - 30f), size = Size(w * 1.24f, 46f),
    )
    val eyePath = Path().apply {
        moveTo(x0, cy)
        quadraticTo(cx, cy - 2f * up, x1, cy)
        quadraticTo(cx, cy + 2f * down, x0, cy)
        close()
    }
    drawPath(eyePath, Color(0xFFF7F2EE))
    clipPath(eyePath) {
        drawCircle(Iris, 17f, Offset(cx + gx, cy - 4f + gy))
        drawCircle(Color(0xFF16100C), 8f, Offset(cx + gx, cy - 4f + gy))
        drawCircle(Color.White.copy(alpha = 0.9f), 3.5f, Offset(cx + gx + 6f, cy - 10f + gy))
    }
    val lid = Path().apply {
        moveTo(x0, cy)
        quadraticTo(cx, cy - 2f * up, x1, cy)
    }
    drawPath(lid, Lash, style = Stroke(6f, cap = StrokeCap.Round))
    drawLine(Lash, Offset(cx + outer * w / 2f, cy), Offset(cx + outer * (w / 2f + 14f), cy - 9f), 5f, StrokeCap.Round)
}

private fun DrawScope.mouth(f: Face, open: Float) {
    val cx = 500f
    val cy = 584f
    val hw = 54f
    val yl = cy - f.smile * 8f
    val yr = cy - f.smile * 8f - f.smirk
    val o = open.coerceIn(0f, 1f)

    val cavity = Path().apply {
        moveTo(cx - hw, yl)
        quadraticTo(cx, cy + 4f, cx + hw, yr)
        quadraticTo(cx, cy + 4f + 68f * o, cx - hw, yl)
        close()
    }
    if (o > 0.04f) {
        drawPath(cavity, Color(0xFF3A1519))
        clipPath(cavity) {
            drawRect(Color(0xFFF8F2EA), topLeft = Offset(cx - hw, cy - 4f), size = Size(hw * 2f, 11f + 12f * o))
        }
    }
    val upper = Path().apply {
        moveTo(cx - hw, yl)
        quadraticTo(cx - 28f, cy - 24f, cx, cy - 13f)
        quadraticTo(cx + 28f, cy - 24f, cx + hw, yr)
        quadraticTo(cx, cy + 4f, cx - hw, yl)
        close()
    }
    val lower = Path().apply {
        moveTo(cx - hw, yl)
        quadraticTo(cx, cy + 4f + 68f * o, cx + hw, yr)
        quadraticTo(cx, cy + 50f + 68f * o, cx - hw, yl)
        close()
    }
    drawPath(lower, f.lip)
    drawPath(upper, lerp(f.lip, Color.Black, 0.12f))
    drawOval(Color.White.copy(alpha = 0.16f), Offset(cx - 16f, cy + 16f + 34f * o), Size(32f, 8f))
}

private fun DrawScope.bangs() {
    val p = Path().apply {
        moveTo(344f, 470f)
        cubicTo(322f, 300f, 415f, 212f, 520f, 212f)
        cubicTo(645f, 212f, 680f, 330f, 658f, 470f)
        cubicTo(640f, 372f, 590f, 330f, 520f, 326f)
        cubicTo(430f, 322f, 372f, 380f, 344f, 470f)
        close()
    }
    drawPath(p, Brush.linearGradient(listOf(HairLight, HairDark), start = Offset(360f, 210f), end = Offset(640f, 430f)))
    val strand = Path().apply {
        moveTo(385f, 330f)
        cubicTo(430f, 250f, 540f, 232f, 610f, 270f)
    }
    drawPath(strand, Color.White, alpha = 0.10f, style = Stroke(10f, cap = StrokeCap.Round))
}
