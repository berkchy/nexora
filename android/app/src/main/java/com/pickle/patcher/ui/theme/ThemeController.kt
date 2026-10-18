package com.pickle.patcher.ui.theme

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which of the three brightness modes the app runs in. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: System
    }
}

/** What the picker holds: a preset id, or the three colours of a custom one. */
data class ThemeChoice(
    val themeId: String = NexoraPalette.id,
    val mode: ThemeMode = ThemeMode.System,
    val custom: Triple<Long, Long, Long>? = null,
) {
    val palette: Palette get() = paletteFor(themeId, custom)
}

/**
 * Holds the theme choice and writes it through immediately, so a process death
 * between picking a colour and backgrounding the app cannot lose it.
 *
 * A ViewModel rather than remember{} so the choice survives configuration
 * changes without the whole tree re-reading SharedPreferences on every
 * recomposition, which is what a plain remember would do.
 */
class ThemeController(app: Application) : AndroidViewModel(app) {

    private val prefs by lazy {
        app.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    }

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    private fun read(): ThemeChoice {
        val custom = prefs.getLong("custom_primary", -1L).takeIf { it >= 0 }?.let { a ->
            prefs.getLong("custom_secondary", -1L).takeIf { it >= 0 }?.let { b ->
                prefs.getLong("custom_tertiary", -1L).takeIf { it >= 0 }?.let { c ->
                    Triple(a, b, c)
                }
            }
        }
        return ThemeChoice(
            themeId = prefs.getString("theme_id", NexoraPalette.id) ?: NexoraPalette.id,
            mode = ThemeMode.fromKey(prefs.getString("mode", null)),
            custom = custom,
        )
    }

    private fun write(next: ThemeChoice) {
        _choice.value = next
        prefs.edit().apply {
            putString("theme_id", next.themeId)
            putString("mode", next.mode.key)
            val c = next.custom
            if (c == null) {
                remove("custom_primary")
                remove("custom_secondary")
                remove("custom_tertiary")
            } else {
                putLong("custom_primary", c.first)
                putLong("custom_secondary", c.second)
                putLong("custom_tertiary", c.third)
            }
        }.apply()
    }

    fun select(themeId: String) = write(_choice.value.copy(themeId = themeId))

    fun setMode(mode: ThemeMode) = write(_choice.value.copy(mode = mode))

    /**
     * Saves the three picked colours and switches to them. ARGB longs rather
     * than strings so a half-written value cannot come back as a bad parse.
     */
    fun selectCustom(primary: Long, secondary: Long, tertiary: Long) =
        write(_choice.value.copy(themeId = "custom", custom = Triple(primary, secondary, tertiary)))

    fun clearCustom() = write(_choice.value.copy(themeId = NexoraPalette.id, custom = null))
}