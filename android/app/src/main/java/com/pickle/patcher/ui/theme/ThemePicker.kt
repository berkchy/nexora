package com.pickle.patcher.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Which of the three wells a candidate colour will land in. */
private enum class Well { Primary, Secondary, Tertiary }

/**
 * The theme chooser.
 *
 * A dialog rather than a route, because a theme has to stay visible while it is
 * being picked: every tap repaints the app behind the sheet, which is the only
 * way to tell two three-colour palettes apart before committing to one.
 */
@Composable
fun ThemePickerDialog(
    choice: ThemeChoice,
    onPick: (String) -> Unit,
    onMode: (ThemeMode) -> Unit,
    onCustom: (Long, Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var customOpen by remember { mutableStateOf(choice.themeId == "custom") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = choice.mode == mode,
                            onClick = { onMode(mode) },
                            label = {
                                Text(
                                    mode.label,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(252.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(Presets) { preset ->
                        ThemeSwatch(
                            label = preset.name,
                            colors = Triple(preset.primary, preset.secondary, preset.tertiary),
                            selected = choice.themeId == preset.id,
                            onClick = {
                                customOpen = false
                                onPick(preset.id)
                            },
                        )
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        ThemeSwatch(
                            label = "Custom",
                            colors = Triple(
                                currentOf(choice, Well.Primary),
                                currentOf(choice, Well.Secondary),
                                currentOf(choice, Well.Tertiary),
                            ),
                            selected = choice.themeId == "custom",
                            onClick = { customOpen = !customOpen },
                        )
                    }
                }

                if (customOpen) {
                    Spacer(Modifier.height(12.dp))
                    CustomThemeEditor(
                        initial = Triple(
                            currentOf(choice, Well.Primary),
                            currentOf(choice, Well.Secondary),
                            currentOf(choice, Well.Tertiary),
                        ),
                        onApply = { triple ->
                            onPick("custom")
                            onCustom(
                                triple.first.value.toLong(),
                                triple.second.value.toLong(),
                                triple.third.value.toLong(),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }

/** The colour a well currently holds, falling back to the current palette. */
private fun currentOf(choice: ThemeChoice, well: Well): Color =
    when (well) {
        Well.Primary -> choice.palette.primary
        Well.Secondary -> choice.palette.secondary
        Well.Tertiary -> choice.palette.tertiary
    }

/**
 * One theme's identity: the three colours together, so the swatch shows the
 * whole palette rather than only its primary.
 */
@Composable
private fun ThemeSwatch(
    label: String,
    colors: Triple<Color, Color, Color>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (selected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(10.dp),
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SwatchDot(colors.first)
                SwatchDot(colors.second)
                SwatchDot(colors.third)
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * One of the three colours. The dark rim keeps a dot visible even when the
 * colour it shows is the same as the swatch behind it.
 */
@Composable
private fun SwatchDot(color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = 0.22f), CircleShape),
    )
}

/**
 * Pick a colour for each of the three wells.
 *
 * The candidates are fixed rather than a full picker on purpose: they are
 * opaque, they are spread far enough apart to tell apart at a glance, and
 * every one of them was picked to sit legibly on both a light and a dark
 * background - which a free HSV slider does not promise.
 */
@Composable
private fun CustomThemeEditor(
    initial: Triple<Color, Color, Color>,
    onApply: (Triple<Color, Color, Color>) -> Unit,
) {
    var colors by remember { mutableStateOf(initial) }
    var target by remember { mutableStateOf(Well.Primary) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Well.entries.forEach { well ->
                val color = when (well) {
                    Well.Primary -> colors.first
                    Well.Secondary -> colors.second
                    Well.Tertiary -> colors.third
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { target = well },
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (target == well) 3.dp else 1.dp,
                                color = if (target == well) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            ),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(well.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Candidates.forEach { candidate ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(candidate)
                        .border(1.dp, Color.Black.copy(alpha = 0.18f), CircleShape)
                        .clickable {
                            colors = when (target) {
                                Well.Primary -> Triple(candidate, colors.second, colors.third)
                                Well.Secondary -> Triple(colors.first, candidate, colors.third)
                                Well.Tertiary -> Triple(colors.first, colors.second, candidate)
                            }
                        },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { onApply(colors) }) { Text("Apply") }
    }
}

private val Well.label: String
    get() = when (this) {
        Well.Primary -> "Primary"
        Well.Secondary -> "Secondary"
        Well.Tertiary -> "Tertiary"
    }

/**
 * Fifteen opaque colours spread around the wheel and up the value scale. Fixed
 * so the list is reviewable and cannot drift with the theme in use.
 */
private val Candidates: List<Color> = buildList {
    for (hue in listOf(0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f)) {
        add(Color.hsv(hue, 0.72f, 0.92f))
    }
    for (hue in listOf(210f, 270f, 330f)) {
        add(Color.hsv(hue, 0.30f, 0.98f))
    }
}