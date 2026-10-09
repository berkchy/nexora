package com.pickle.patcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.R
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.AccentDim
import com.pickle.patcher.ui.theme.Black
import com.pickle.patcher.ui.theme.Gray99
import com.pickle.patcher.ui.theme.Gray50
import com.pickle.patcher.ui.theme.Secondary
import com.pickle.patcher.ui.theme.Tertiary
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.delay

// Boot sequence, run as one timeline so the stages stay locked to each other
// instead of racing a pile of independent animations.
private const val ENTER_MS = 1750
private const val HOLD_MS = 620
private const val EXIT_MS = 720

private const val LOGO_SIZE = 124.dp

private val Transparent = Color(0x00000000)

private const val TITLE = "NEXORA"
private const val SUBTITLE = "Counter-Strike patcher"

// Stage boundaries inside the entrance timeline, as a fraction of it.
private const val T_RING = 0.04f
private const val T_LOGO = 0.10f
private const val T_TITLE = 0.26f
private const val T_SUB = 0.52f

/** Ramps a value from 0 to 1 over the window [start]..[end] of the timeline. */
private fun stage(t: Float, start: Float, end: Float): Float =
    ((t - start) / (end - start)).coerceIn(0f, 1f)

@Composable
fun IntroOverlay(playKey: Long = 0L) {
    var visible by remember { mutableStateOf(false) }
    val timeline = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(playKey) {
        visible = true
        timeline.snapTo(0f)
        exit.snapTo(0f)
        timeline.animateTo(1f, tween(ENTER_MS, easing = LinearOutSlowInEasing))
        delay(HOLD_MS)
        exit.animateTo(1f, tween(EXIT_MS, easing = EaseInCubic))
        visible = false
    }

    if (!visible) return

    val t = timeline.value
    val x = exit.value

    // Background breathes on its own loop so it is already alive when the logo
    // starts moving, and keeps moving under the exit.
    val drift = rememberInfiniteTransition(label = "introDrift")
    val aurora by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(7200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora",
    )
    val spin by drift.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(5200, easing = LinearEasing)),
        label = "spin",
    )
    val pulseWave by drift.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = EaseOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseWave",
    )

    val ringT = stage(t, T_RING, 0.46f)
    val logoT = stage(t, T_LOGO, 0.52f)
    val subT = stage(t, T_SUB, 0.88f)

    // One scale for the whole stack during the exit: it grows past the camera
    // and dissolves, so the app is revealed rather than covered.
    val push = 1f + 0.22f * EaseOutCubic(x)
    val fade = (1f - x * x).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallow taps without drawing anything: an intro you can skip by
            // brushing the screen goes off before the app has even finished
            // starting, which reads as a glitch. It plays for its full hold and
            // leaves on its own.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Consume on the Initial pass so the tap never reaches the
                        // app underneath, before anything else sees it.
                        awaitPointerEvent(PointerEventPass.Initial)
                            .changes
                            .forEach { it.consume() }
                    }
                }
            },
    ) {
        // ---- background -------------------------------------------------
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    0f to Black,
                    0.55f to Gray99,
                    1f to Black,
                ),
            )

            // Two drifting colour fields. Screen keeps them from muddying into
            // grey where they overlap.
            val slow = aurora * 0.5f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Accent.copy(alpha = 0.30f), Transparent),
                    center = Offset(w * (0.22f + 0.14f * slow), h * (0.26f + 0.10f * aurora)),
                    radius = w * 0.78f,
                ),
                blendMode = BlendMode.Screen,
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Secondary.copy(alpha = 0.26f), Transparent),
                    center = Offset(w * (0.82f - 0.12f * aurora), h * (0.66f - 0.12f * slow)),
                    radius = w * 0.70f,
                ),
                blendMode = BlendMode.Screen,
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Tertiary.copy(alpha = 0.10f), Transparent),
                    center = Offset(w * 0.5f, h * (1.02f - 0.06f * aurora),
                    ),
                    radius = w * 0.85f,
                ),
                blendMode = BlendMode.Screen,
            )

            // Faint technical grid, drifting slowly upward so the field never
            // looks like a static texture.
            val cell = 44.dp.toPx()
            val gridAlpha = 0.055f * fade
            val offsetY = (t * 260f) % cell
            var gx = 0f
            while (gx <= w) {
                drawLine(
                    color = Gray50.copy(alpha = gridAlpha),
                    start = Offset(gx, offsetY),
                    end = Offset(gx, h + offsetY),
                    strokeWidth = 1f,
                )
                gx += cell
            }
            var gy = offsetY
            while (gy <= h + cell) {
                drawLine(
                    color = Gray50.copy(alpha = gridAlpha),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f,
                )
                gy += cell
            }

            // Horizon glow under the logo.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(AccentDim.copy(alpha = 0.55f * pulseWave), Transparent),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.85f,
                ),
                blendMode = BlendMode.Screen,
            )
        }

        // ---- logo stack -------------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .scale(push)
                .graphicsLayer { alpha = fade },
            contentAlignment = Alignment.Center,
        ) {
            // Counter-rotating rings around the mark. The outer one only shows
            // up after the inner has drawn most of itself, so the ring appears
            // to be assembled rather than simply faded in.
            Canvas(Modifier.size(LOGO_SIZE * 2.1f).rotate(spin)) {
                val r = size.minDimension / 2f
                val width = 2.dp.toPx()

                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Accent, Transparent, Secondary, Transparent, Accent),
                    ),
                    radius = r,
                    style = Stroke(width = width, cap = StrokeCap.Round),
                    alpha = 0.85f * ringT,
                )
            }
            Canvas(
                Modifier
                    .size(LOGO_SIZE * 1.55f)
                    .rotate(-spin * 1.6f),
            ) {
                val r = size.minDimension / 2f
                drawArc(
                    brush = Brush.sweepGradient(listOf(Accent, Transparent, Tertiary, Transparent)),
                    startAngle = 0f,
                    sweepAngle = 260f * ringT,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.9f,
                )
            }

            // The mark itself: overshoots slightly on the way in, then settles.
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(LOGO_SIZE)
                    .scale(
                        // spring-ish overshoot without pulling in a physics
                        // dependency: fast rise, small peak, settle
                        val s = stage(t, T_LOGO, 0.52f)
                        val eased = if (s < 1f) 1f - (1f - s) * (1f - s) else 1f
                        val overshoot = 1f + 0.10f * EaseOutCubic((s - 0.55f) / 0.45f).coerceIn(0f, 1f) * (1f - s)
                        (0.72f + 0.28f * eased + overshoot) * (1f + 0.06f * pulseWave * s)
                    )
                    .graphicsLayer { alpha = EaseOutCubic(logoT) },
            )
        }

        // ---- title + subtitle -------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = LOGO_SIZE * 1.25f)
                .scale(push)
                .graphicsLayer { alpha = fade },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = White,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp,
                    modifier = Modifier.graphicsLayer {
                        // Settles out of a slight over-scale, so the wordmark
                        // resolves rather than simply appearing.
                        val scale = 1f + 0.045f * (1f - stage(t, T_TITLE, 0.86f))
                        alpha = stage(t, T_TITLE, T_TITLE + 0.34f)
                        scaleX = scale
                        scaleY = scale
                    },
                )
                Text(
                    text = SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray50,
                    letterSpacing = 1.sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = stage(t, T_SUB, 0.92f)
                        translationY = 10.dp.toPx() * (1f - stage(t, T_SUB, 1f))
                    },
                )
            }
        }

        // A thin line that draws itself under the title and drains away on exit,
        // the one element that reads as a progress bar for the launch.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = LOGO_SIZE * 1.95f)
                .size(width = 210.dp * push, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .graphicsLayer { alpha = fade },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(color = Gray50.copy(alpha = 0.18f))
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Transparent, Accent, Secondary, Transparent),
                    ),
                    size = Size(size.width * stage(t, 0.16f, 0.72f), size.height),
                )
            }
        }
    }
}