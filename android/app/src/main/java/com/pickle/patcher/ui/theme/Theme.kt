package com.pickle.patcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The controller, reachable from anywhere below NexoraTheme. The picker lives
 * in the drawer, which is rendered inside the same theme that would otherwise
 * have to be rebuilt to show it; going through the local lets it read the
 * controller without threading it through every screen's parameters.
 */
private val NexoraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController was read outside NexoraTheme")
}

/**
 * Resolves [choice] to a scheme and publishes it as MaterialTheme.
 *
 * System mode follows the platform, which is read here rather than in the
 * picker so flipping the phone's setting repaints the app without reopening it.
 */
@Composable
fun NexoraTheme(
    choice: ThemeChoice,
    controller: ThemeController,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (choice.mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val colorScheme = if (choice.themeId == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) softenDarkScheme(dynamicDarkColorScheme(context)) else dynamicLightColorScheme(context)
    } else {
        schemeFor(choice.palette, dark)
    }
    CompositionLocalProvider(LocalThemeController provides controller) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexoraTypography,
            shapes = NexoraShapes,
            content = content,
        )
    }
}