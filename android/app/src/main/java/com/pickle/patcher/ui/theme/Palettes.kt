package com.pickle.patcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * A theme is three colours. Everything else - containers, surfaces, outlines,
 * the greys - is derived from those three, so a theme is three swatches wide
 * and stays coherent in both light and dark instead of being two hand-tuned
 * schemes that drift apart.
 */
data class Palette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

/**
 * Mixes [a] toward [b] by [t]: 0 leaves a, 1 returns b. Used instead of
 * Color.copy(alpha) because every role needs an opaque colour - a
 * semi-transparent scheme breaks surfaces stacked on surfaces.
 */
private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

private val BLACK = Color(0xFF000000)
private val WHITE = Color(0xFFFFFFFF)

/**
 * Builds the full scheme for a palette.
 *
 * Dark mixes every neutral toward black and lifts the accents so they stay
 * legible on it; light does the mirror image. The accent containers are the
 * same hue pushed away from the background rather than a fixed dark shade,
 * which is what keeps a lime theme from ending up with a navy button.
 */
fun schemeFor(p: Palette, dark: Boolean): ColorScheme = if (dark) darkOf(p) else lightOf(p)

private fun darkOf(p: Palette): ColorScheme {
    val accent = mix(p.primary, WHITE, 0.30f)
    val secondary = mix(p.secondary, WHITE, 0.30f)
    val tertiary = mix(p.tertiary, WHITE, 0.30f)

    return darkColorScheme(
        primary = accent,
        onPrimary = mix(p.primary, BLACK, 0.82f),
        primaryContainer = mix(p.primary, BLACK, 0.60f),
        onPrimaryContainer = mix(p.primary, WHITE, 0.88f),
        secondary = secondary,
        onSecondary = mix(p.secondary, BLACK, 0.82f),
        secondaryContainer = mix(p.secondary, BLACK, 0.60f),
        onSecondaryContainer = mix(p.secondary, WHITE, 0.88f),
        tertiary = tertiary,
        onTertiary = mix(p.tertiary, BLACK, 0.82f),
        tertiaryContainer = mix(p.tertiary, BLACK, 0.60f),
        onTertiaryContainer = mix(p.tertiary, WHITE, 0.88f),
        background = mix(p.primary, BLACK, 0.95f),
        onBackground = mix(p.primary, WHITE, 0.95f),
        surface = mix(p.primary, BLACK, 0.91f),
        onSurface = mix(p.primary, WHITE, 0.95f),
        surfaceVariant = mix(p.secondary, BLACK, 0.82f),
        onSurfaceVariant = mix(p.primary, WHITE, 0.68f),
        surfaceContainerLowest = mix(p.primary, BLACK, 0.98f),
        surfaceContainerLow = mix(p.primary, BLACK, 0.93f),
        surfaceContainer = mix(p.primary, BLACK, 0.89f),
        surfaceContainerHigh = mix(p.primary, BLACK, 0.85f),
        surfaceContainerHighest = mix(p.primary, BLACK, 0.80f),
        outline = mix(p.primary, WHITE, 0.42f),
        outlineVariant = mix(p.primary, WHITE, 0.24f),
        error = AlertRed,
        onError = BLACK,
        errorContainer = mix(AlertRed, BLACK, 0.70f),
        onErrorContainer = mix(AlertRed, WHITE, 0.80f),
        inverseSurface = mix(p.primary, WHITE, 0.88f),
        inverseOnSurface = mix(p.primary, BLACK, 0.88f),
        inversePrimary = mix(p.primary, BLACK, 0.30f),
        scrim = BLACK,
    )
}

private fun lightOf(p: Palette): ColorScheme {
    val accent = mix(p.primary, BLACK, 0.18f)
    val secondary = mix(p.secondary, BLACK, 0.22f)
    val tertiary = mix(p.tertiary, BLACK, 0.28f)

    return lightColorScheme(
        primary = accent,
        onPrimary = WHITE,
        primaryContainer = mix(p.primary, WHITE, 0.80f),
        onPrimaryContainer = mix(p.primary, BLACK, 0.60f),
        secondary = secondary,
        onSecondary = WHITE,
        secondaryContainer = mix(p.secondary, WHITE, 0.80f),
        onSecondaryContainer = mix(p.secondary, BLACK, 0.60f),
        tertiary = tertiary,
        onTertiary = WHITE,
        tertiaryContainer = mix(p.tertiary, WHITE, 0.76f),
        onTertiaryContainer = mix(p.tertiary, BLACK, 0.62f),
        background = mix(p.primary, WHITE, 0.97f),
        onBackground = mix(p.primary, BLACK, 0.88f),
        surface = mix(p.primary, WHITE, 0.99f),
        onSurface = mix(p.primary, BLACK, 0.88f),
        surfaceVariant = mix(p.secondary, WHITE, 0.88f),
        onSurfaceVariant = mix(p.primary, BLACK, 0.55f),
        surfaceContainerLowest = WHITE,
        surfaceContainerLow = mix(p.primary, WHITE, 0.96f),
        surfaceContainer = mix(p.primary, WHITE, 0.93f),
        surfaceContainerHigh = mix(p.primary, WHITE, 0.90f),
        surfaceContainerHighest = mix(p.primary, WHITE, 0.86f),
        outline = mix(p.primary, BLACK, 0.42f),
        outlineVariant = mix(p.primary, BLACK, 0.18f),
        error = mix(AlertRed, BLACK, 0.25f),
        onError = WHITE,
        errorContainer = mix(AlertRed, WHITE, 0.84f),
        onErrorContainer = mix(AlertRed, BLACK, 0.62f),
        inverseSurface = mix(p.primary, BLACK, 0.85f),
        inverseOnSurface = mix(p.primary, WHITE, 0.92f),
        inversePrimary = mix(p.primary, WHITE, 0.35f),
        scrim = BLACK,
    )
}

/** The default, and the fallback for an id that no longer exists. */
val NexoraPalette = Palette(
    id = "nexora",
    name = "Nexora",
    primary = Color(0xFF7CC7FF),
    secondary = Color(0xFFC6BFFF),
    tertiary = Color(0xFFFFB871),
)

val Presets: List<Palette> = listOf(
    NexoraPalette,
    Palette("ember", "Ember", Color(0xFFFF8A4C), Color(0xFFFF5E5B), Color(0xFFFFD166)),
    Palette("toxic", "Toxic", Color(0xFF9BE564), Color(0xFF34D399), Color(0xFFD9F99D)),
    Palette("ice", "Ice", Color(0xFF67E8F9), Color(0xFF60A5FA), Color(0xFFA5B4FC)),
    Palette("grape", "Grape", Color(0xFFC084FC), Color(0xFFF472B6), Color(0xFFE879F9)),
    Palette("rust", "Rust", Color(0xFFD9A066), Color(0xFFB45309), Color(0xFFE7C99B)),
    Palette("ocean", "Ocean", Color(0xFF60A5FA), Color(0xFF2DD4BF), Color(0xFF0EA5E9)),
    Palette("neon", "Neon", Color(0xFFF0ABFC), Color(0xFF22D3EE), Color(0xFFA3E635)),
    Palette("sand", "Sand", Color(0xFFD6BD8B), Color(0xFFA3B18A), Color(0xFFE9D8A6)),
    Palette("blood", "Blood", Color(0xFFEF4444), Color(0xFF9F1239), Color(0xFFFCA5A5)),
)

private val byId = Presets.associateBy { it.id }

/** The preset with this id, or the default when the id is stale or custom. */
fun presetById(id: String?): Palette? = id?.let { byId[it] }

/**
 * The palette a saved choice resolves to. A custom choice keeps whatever the
 * user picked rather than snapping back to a preset when they reopen the app.
 */
fun paletteFor(themeId: String, custom: Triple<Long, Long, Long>?): Palette =
    presetById(themeId)
        ?: custom?.let { Palette("custom", "Custom", Color(it.first), Color(it.second), Color(it.third)) }
        ?: NexoraPalette