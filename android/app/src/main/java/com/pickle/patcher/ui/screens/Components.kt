package com.pickle.patcher.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.pickle.patcher.ui.theme.Accent
import com.pickle.patcher.ui.theme.Gray30
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.Gray70
import com.pickle.patcher.ui.theme.Gray85
import com.pickle.patcher.ui.theme.Gray99
import com.pickle.patcher.ui.theme.White

private val CardShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)


@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    // Set for actions that replace something the user already has (an APK
    // update, for instance): the button itself has to look like the update.
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.secondary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Accent,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Gray40)
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

@Composable
fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Gray85,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(min = 80.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Gray40,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun AppProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "progress",
    )
    Column(modifier) {
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${(animated * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = Gray40,
        )
    }
}


/**
 * Circular progress with the percentage in the middle. Used by the patch
 * screen so the whole run has one anchor instead of a bar that only grows.
 */
@Composable
fun RingProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 92.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 8.dp,
    label: String = "",
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "ring",
    )
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Gray70,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(animated * 100).toInt()}%",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (label.isNotEmpty()) {
                Text(text = label, color = Gray40, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Ten-line live console. Keeps the tail of the log visible while the run is
 * going, and stays scrollable so the user can read what already scrolled by.
 */
@Composable
fun MiniConsole(
    lines: List<String>,
    modifier: Modifier = Modifier,
    visibleLines: Int = 10,
) {
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Gray99,
    ) {
        Column(
            modifier = Modifier
                .height((visibleLines * 16 + 16).dp)
                .verticalScroll(scroll)
                .padding(vertical = 8.dp, horizontal = 10.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = "waiting…",
                    color = Gray60,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    color = if (line.startsWith("+")) Accent else Gray30,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
