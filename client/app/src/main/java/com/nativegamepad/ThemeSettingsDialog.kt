package com.nativegamepad

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.text.TextWatcher
import android.text.Editable

class ThemeSettingsDialog(private val context: Context, private val onThemeChanged: () -> Unit) {

    private var tempPrimary = ThemeConfig.primaryColor
    private var isUpdatingHexProgrammatically = false

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_theme_settings, null)
        
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Custom Theme")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                ThemeConfig.primaryColor = tempPrimary
                ThemeConfig.save(context)
                onThemeChanged()
            }
            .setNegativeButton("Cancel", null)
            .create()

        val colorPicker = view.findViewById<ColorPickerView>(R.id.colorPicker)
        val etHexCode = view.findViewById<EditText>(R.id.etHexCode)

        fun updateHexCode(color: Int) {
            isUpdatingHexProgrammatically = true
            etHexCode.setText(String.format("#%06X", 0xFFFFFF and color))
            isUpdatingHexProgrammatically = false
        }

        colorPicker.onColorChanged = { color ->
            tempPrimary = color
            updateHexCode(color)
        }

        etHexCode.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingHexProgrammatically) return
                
                val hex = s?.toString() ?: ""
                if (hex.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                    try {
                        val parsed = Color.parseColor(hex)
                        tempPrimary = parsed
                        colorPicker.setColor(parsed)
                    } catch (e: Exception) {}
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Init
        colorPicker.setColor(tempPrimary)
        updateHexCode(tempPrimary)

        dialog.show()
    }
}
