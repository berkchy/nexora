package com.pickle.patcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The controller, reachable from anywhere below NexoraTheme. The picker lives
 * in the drawer, which is rendered inside the same theme that would otherwise
 * have to be rebuilt to show it; going through the local lets it read the
 * controller without threading it through every screen's parameters.
 */
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

    CompositionLocalProvider(LocalThemeController provides controller) {
        MaterialTheme(
            colorScheme = schemeFor(choice.palette, dark),
            typography = NexoraTypography,
            content = content,
        )
    }
}