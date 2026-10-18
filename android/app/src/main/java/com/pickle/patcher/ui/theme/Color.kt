package com.pickle.patcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Semantic colours. These mean the same thing in every theme, so they stay
// fixed: a red here is always "this failed", whatever the palette does.
val AlertRed = Color(0xFFFF8A80)
val WarnYellow = Color(0xFFFFB871)
val SuccessGreen = Color(0xFF7BD88F)
val SuccessGreenDim = Color(0xFF173D24)

// Terminal ink. The plugin editor draws its own dark code surface, and text
// sitting on an accent fill needs to stay at the extreme, so neither of these
// follows the theme.
val Black = Color(0xFF0C0E13)
val White = Color(0xFFF7F9FC)

/**
 * The palette-driven names.
 *
 * These were top-level vals when the app had exactly one theme and every call
 * site named them directly. With ten palettes plus a custom one that stops
 * working: a hardcoded sky blue would stay sky blue in the Blood theme. Making
 * them composable getters reads the scheme at the point of use instead, so the
 * few hundred existing references follow the theme without being rewritten -
 * and a call site that ever moves into a non-composable context fails to
 * compile instead of quietly freezing one colour.
 */
val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

val AccentDim: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer

val AccentSurface: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer

val Secondary: Color
    @Composable get() = MaterialTheme.colorScheme.secondary

val SecondaryDim: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer

val SecondarySurface: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer

val Tertiary: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

val TertiaryDim: Color
    @Composable get() = MaterialTheme.colorScheme.tertiaryContainer

// Neutrals, mapped onto the scheme's own ramp so they stay tinted by whatever
// palette is active instead of reading as the same grey in all ten themes.
val Gray99: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

val Gray95: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow

val Gray90: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

val Gray85: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

val Gray80: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

val Gray70: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant

val Gray60: Color
    @Composable get() = MaterialTheme.colorScheme.outline

val Gray50: Color
    @Composable get() = MaterialTheme.colorScheme.outline

val Gray40: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

val Gray30: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

val Gray20: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

val Gray10: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface