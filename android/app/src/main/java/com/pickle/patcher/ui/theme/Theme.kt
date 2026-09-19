package com.pickle.patcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

val NexoraDarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Black,
    primaryContainer = AccentDim,
    onPrimaryContainer = White,
    secondary = Secondary,
    onSecondary = Black,
    secondaryContainer = SecondaryDim,
    onSecondaryContainer = White,
    tertiary = Tertiary,
    onTertiary = Black,
    tertiaryContainer = TertiaryDim,
    onTertiaryContainer = White,
    background = Black,
    onBackground = White,
    surface = Gray95,
    onSurface = White,
    surfaceVariant = Gray85,
    onSurfaceVariant = Gray40,
    surfaceContainerLowest = Gray99,
    surfaceContainerLow = Gray95,
    surfaceContainer = Gray90,
    surfaceContainerHigh = Gray85,
    surfaceContainerHighest = Gray80,
    outline = Gray60,
    outlineVariant = Gray70,
    error = AlertRed,
    onError = Black,
    errorContainer = AlertRedDim,
    onErrorContainer = AlertRed,
    inverseSurface = Gray10,
    inverseOnSurface = Gray90,
    inversePrimary = AccentDim,
    scrim = Black,
)

@Composable
fun NexoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexoraDarkScheme,
        typography = NexoraTypography,
        content = content,
    )
}
