package com.pickle.patcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.R
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.White
import kotlinx.coroutines.delay

private const val HOLD_MS = 1150L
private const val FADE_IN_MS = 380
private const val FADE_OUT_MS = 380

@Composable
fun IntroOverlay(playKey: Long = 0L) {
    var visible by remember { mutableStateOf(true) }
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(playKey) {
        visible = true
        delay(60)
        entered = true
        delay(HOLD_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(FADE_IN_MS)),
        exit = fadeOut(animationSpec = tween(FADE_OUT_MS)),
    ) {
        val appear by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(620, easing = FastOutSlowInEasing),
            label = "introAppear",
        )
        val glow by rememberInfiniteTransition(label = "introGlow").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "introGlowAlpha",
        )
        val sweep by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(900, delayMillis = 220, easing = FastOutSlowInEasing),
            label = "introSweep",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Swallow taps without drawing anything: an intro you can skip
                // by brushing the screen goes off before the app has even
                // finished starting, which reads as a glitch. It plays for its
                // full hold and fades out on its own.
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
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .graphicsLayer { alpha = glow * appear }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Accent.copy(alpha = 0.30f), Color.Transparent),
                        ),
                        shape = RoundedCornerShape(150.dp),
                    )
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(124.dp)
                        .scale(0.86f + 0.14f * appear)
                        .graphicsLayer { alpha = appear }
                        .clip(RoundedCornerShape(28.dp)),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "NEXORA",
                    style = MaterialTheme.typography.headlineSmall,
                    color = White,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp,
                    modifier = Modifier.graphicsLayer { alpha = appear },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Counter-Strike patcher",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gray40,
                    letterSpacing = 1.sp,
                    modifier = Modifier.graphicsLayer { alpha = appear * 0.9f },
                )
            }
        }
    }
}
