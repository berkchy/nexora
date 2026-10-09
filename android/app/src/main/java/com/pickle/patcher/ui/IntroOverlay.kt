package com.pickle.patcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pickle.patcher.R
import kotlinx.coroutines.delay

// Black screen, the mark, and nothing else. Fade in, hold, fade out.
private const val FADE_IN_MS = 520
private const val HOLD_MS = 900L
private const val FADE_OUT_MS = 520

private val LOGO_SIZE = 132.dp

private val Backdrop = Color(0xFF000000)

@Composable
fun IntroOverlay(playKey: Long = 0L) {
    var visible by remember { mutableStateOf(false) }
    val timeline = remember { Animatable(0f) }

    LaunchedEffect(playKey) {
        visible = true
        timeline.snapTo(0f)
        timeline.animateTo(1f, tween(FADE_IN_MS, easing = LinearOutSlowInEasing))
        delay(HOLD_MS)
        timeline.animateTo(2f, tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
        visible = false
    }

    if (!visible) return

    // 0..1 fades in, 1..2 fades back out.
    val t = timeline.value
    val alpha = if (t <= 1f) t else (2f - t).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Backdrop)
            .graphicsLayer { this.alpha = alpha }
            // Swallow taps: an intro you can skip by brushing the screen goes
            // off before the app has finished starting, which reads as a
            // glitch. It plays for its full hold and leaves on its own.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Consume on the Initial pass so the tap never reaches
                        // the app underneath, before anything else sees it.
                        awaitPointerEvent(PointerEventPass.Initial)
                            .changes
                            .forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(LOGO_SIZE),
        )
    }
}