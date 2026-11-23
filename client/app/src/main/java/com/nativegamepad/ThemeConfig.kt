package com.nativegamepad

import android.content.Context
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

object ThemeConfig {
    private const val PREFS_NAME = "NyxxThemePrefs"

    var primaryColor: Int = "#888888".toColorInt() // Grey
    var backgroundColor: Int = "#121212".toColorInt() // Charcoal background

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        primaryColor = prefs.getInt("primaryColor", "#888888".toColorInt())
        backgroundColor = prefs.getInt("backgroundColor", "#121212".toColorInt())
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putInt("primaryColor", primaryColor)
            putInt("backgroundColor", backgroundColor)
        }
    }
}
