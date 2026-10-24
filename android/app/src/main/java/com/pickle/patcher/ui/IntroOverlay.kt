package com.pickle.patcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pickle.patcher.R
import kotlinx.coroutines.launch

private const val PHASE1_MS = 2000
private const val PHASE2_MS = 800
private const val HOLD_MS = 300
private const val FADE_OUT_MS = 600
private const val LOGO_SIZE = 132

@Composable
fun IntroOverlay(playKey: Long = 0L) {
    var visible by remember { mutableStateOf(false) }
    val phase1 = remember { Animatable(0f) }   // 0..1 vignette slides left away
    val phase2 = remember { Animatable(0f) }   // 0..1 vignette from right covers
    val logoScale = remember { Animatable(0.8f) }
    val fadeOut = remember { Animatable(1f) }

    LaunchedEffect(playKey) {
        visible = true
        phase1.snapTo(0f); phase2.snapTo(0f); logoScale.snapTo(0.8f); fadeOut.snapTo(1f)
        launch { logoScale.animateTo(1f, tween(PHASE1_MS, easing = FastOutSlowInEasing)) }
        phase1.animateTo(1f, tween(PHASE1_MS, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(200)
        phase2.animateTo(1f, tween(PHASE2_MS, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(HOLD_MS.toLong())
        fadeOut.animateTo(0f, tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
        visible = false
    }

    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { this.alpha = fadeOut.value }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                            .changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(LOGO_SIZE.dp)
                .graphicsLayer {
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                },
        )

        // Initial vignette covering the logo: slides left, away from it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -size.width * phase1.value
                    alpha = (1f - phase1.value).coerceIn(0f, 1f)
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Black, Color.Black.copy(alpha = 0.6f), Color.Transparent),
                        radius = 600f,
                    ),
                ),
        )

        // Vignette from the right that sweeps in to cover the logo.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * (1f - phase2.value)
                    alpha = phase2.value.coerceIn(0f, 1f)
                }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f), Color.Black),
                        startX = 0f,
                        endX = Float.POSITIVE_INFINITY,
                    ),
                ),
        )
    }
}
