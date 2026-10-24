package com.pickle.patcher.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the drawer width as a fraction of the screen width, persisted. */
class DrawerWidthController(app: Application) : AndroidViewModel(app) {
    private val prefs by lazy { app.getSharedPreferences("drawer_prefs", Context.MODE_PRIVATE) }
    private val _fraction = MutableStateFlow(prefs.getFloat("width_fraction", 0.5f).coerceIn(0.3f, 0.85f))
    val fraction: StateFlow<Float> = _fraction.asStateFlow()

    fun setFraction(v: Float) {
        val c = v.coerceIn(0.3f, 0.85f)
        _fraction.value = c
        prefs.edit().putFloat("width_fraction", c).apply()
    }
}
