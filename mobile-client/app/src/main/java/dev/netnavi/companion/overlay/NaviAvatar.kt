package dev.netnavi.companion.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class AvatarMode(val animates: Boolean) {
    IDLE(true), PROCESSING(true), TALKING(true), DISCONNECTED(false), KILLED(false)
}

private val BodyTop = Color(0xFF5B8CFF)
private val BodyBottom = Color(0xFF2347B8)
private val Visor = Color(0xFF0B1B3F)
private val Glow = Color(0xFF7FE7FF)
private val Alert = Color(0xFFFF5A5F)
private val GreyTop = Color(0xFF9AA3B5)
private val GreyBottom = Color(0xFF5E6678)

/**
 * Original "orb with a visor" avatar drawn on a Canvas. Animation state is read as lambdas inside
 * graphicsLayer/draw so frames do not trigger recomposition. Static modes do not run a transition.
 */
@Composable
fun NaviAvatar(mode: AvatarMode, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val phase = rememberPhase(mode.animates)
    val bobPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }

    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                translationY = when (mode) {
                    AvatarMode.IDLE, AvatarMode.TALKING -> sin(phase() * 2 * PI.toFloat()) * bobPx
                    AvatarMode.PROCESSING -> sin(phase() * 4 * PI.toFloat()) * bobPx * 0.5f
                    else -> 0f
                }
                alpha = if (mode == AvatarMode.DISCONNECTED) 0.7f else 1f
            },
    ) {
        drawNavi(mode, phase())
    }
}

/** Returns a lambda reading the 0..1 animation phase; static modes get a constant and no transition. */
@Composable
private fun rememberPhase(animated: Boolean): () -> Float {
    if (!animated) return { 0f }
    val state = rememberInfiniteTransition(label = "navi").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    return { state.value }
}

private fun DrawScope.drawNavi(mode: AvatarMode, phase: Float) {
    val s = size.minDimension
    val grey = mode == AvatarMode.DISCONNECTED
    val cx = size.width / 2
    val cy = size.height * 0.58f
    val r = s * 0.34f

    // antenna
    val antTop = Offset(cx, cy - r - s * 0.16f)
    drawLine(if (grey) GreyBottom else Glow, Offset(cx, cy - r + 2f), antTop, strokeWidth = s * 0.035f, cap = StrokeCap.Round)
    val pulse = if (mode == AvatarMode.PROCESSING) 0.6f + 0.4f * abs(sin(phase * 2 * PI.toFloat() * 2)) else 1f
    drawCircle(
        color = when (mode) { AvatarMode.KILLED -> Alert; AvatarMode.DISCONNECTED -> GreyBottom; else -> Glow },
        radius = s * 0.055f * pulse,
        center = antTop,
    )

    // body
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(if (grey) GreyTop else BodyTop, if (grey) GreyBottom else BodyBottom),
            startY = cy - r, endY = cy + r,
        ),
        radius = r,
        center = Offset(cx, cy),
    )

    // processing: a ring segment orbiting the body
    if (mode == AvatarMode.PROCESSING) {
        drawArc(
            color = Glow,
            startAngle = phase * 360f * 2,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(cx - r - s * 0.05f, cy - r - s * 0.05f),
            size = Size((r + s * 0.05f) * 2, (r + s * 0.05f) * 2),
            style = Stroke(width = s * 0.035f, cap = StrokeCap.Round),
        )
    }
    // kill switch engaged: solid red ring
    if (mode == AvatarMode.KILLED) {
        drawCircle(Alert, radius = r + s * 0.04f, center = Offset(cx, cy), style = Stroke(width = s * 0.05f))
    }

    // visor
    val vw = r * 1.45f
    val vh = r * 0.78f
    val vTop = cy - vh / 2
    drawRoundRect(Visor, Offset(cx - vw / 2, vTop), Size(vw, vh), CornerRadius(vh * 0.4f))

    // eyes
    val eyeColor = when (mode) { AvatarMode.KILLED -> Alert; AvatarMode.DISCONNECTED -> GreyTop; else -> Glow }
    val eyeY = vTop + vh * 0.38f
    val eyeDx = vw * 0.22f
    val blink = if (phase in 0.90f..0.95f) 0.12f else 1f
    val eyeH = when (mode) {
        AvatarMode.PROCESSING -> vh * 0.22f
        else -> vh * 0.34f * blink
    }
    val scan = if (mode == AvatarMode.PROCESSING) sin(phase * 2 * PI.toFloat() * 3) * vw * 0.06f else 0f
    when (mode) {
        AvatarMode.DISCONNECTED -> for (sx in listOf(-1, 1)) { // flat "asleep" eyes
            drawLine(eyeColor, Offset(cx + sx * eyeDx - vw * 0.07f, eyeY), Offset(cx + sx * eyeDx + vw * 0.07f, eyeY), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
        }
        AvatarMode.KILLED -> for (sx in listOf(-1, 1)) { // X eyes
            val c = Offset(cx + sx * eyeDx, eyeY)
            val d = vw * 0.06f
            drawLine(eyeColor, c + Offset(-d, -d), c + Offset(d, d), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
            drawLine(eyeColor, c + Offset(-d, d), c + Offset(d, -d), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
        }
        else -> for (sx in listOf(-1, 1)) {
            val w = vw * 0.12f
            drawRoundRect(eyeColor, Offset(cx + sx * eyeDx - w / 2 + scan, eyeY - eyeH / 2), Size(w, eyeH), CornerRadius(w / 2))
        }
    }

    // mouth: opens and closes while talking
    val mouthY = vTop + vh * 0.78f
    when (mode) {
        AvatarMode.TALKING -> {
            val open = abs(sin(phase * 2 * PI.toFloat() * 4))
            val mh = vh * (0.06f + 0.16f * open)
            val mw = vw * 0.22f
            drawRoundRect(eyeColor, Offset(cx - mw / 2, mouthY - mh / 2), Size(mw, mh), CornerRadius(mh / 2))
        }
        AvatarMode.IDLE, AvatarMode.PROCESSING -> {
            val mw = vw * 0.16f
            drawLine(eyeColor, Offset(cx - mw / 2, mouthY), Offset(cx + mw / 2, mouthY), strokeWidth = s * 0.02f, cap = StrokeCap.Round)
        }
        else -> Unit
    }
}
