package com.nativegamepad

import android.content.Context
import android.graphics.Color

object ThemeConfig {
    private const val PREFS_NAME = "NyxxThemePrefs"

    var primaryColor: Int = Color.parseColor("#888888") // Grey
    var backgroundColor: Int = Color.parseColor("#121212") // Charcoal background

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        primaryColor = prefs.getInt("primaryColor", Color.parseColor("#888888"))
        backgroundColor = prefs.getInt("backgroundColor", Color.parseColor("#121212"))
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("primaryColor", primaryColor)
            .putInt("backgroundColor", backgroundColor)
            .apply()
    }
}
