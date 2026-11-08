package com.pickle.patcher.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiScaleController(app: Application) : AndroidViewModel(app) {
    private val prefs by lazy { app.getSharedPreferences("ui_scale_prefs", Context.MODE_PRIVATE) }
    private val _scale = MutableStateFlow(prefs.getFloat("scale", 1.0f).coerceIn(0.5f, 2.0f))
    val scale: StateFlow<Float> = _scale.asStateFlow()

    fun setScale(v: Float) {
        val c = v.coerceIn(0.5f, 2.0f)
        _scale.value = c
        prefs.edit().putFloat("scale", c).apply()
    }
}
