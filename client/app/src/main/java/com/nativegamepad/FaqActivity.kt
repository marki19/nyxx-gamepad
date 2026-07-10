package com.nativegamepad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class FaqActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        findViewById<ImageView>(R.id.btnFaqBack).setOnClickListener { finish() }
        container = findViewById(R.id.faqContainer)

        buildContent()
    }

    private fun buildContent() {
        // ---------- GENERAL ----------
        addHeader("General")
        addQ(
            "Does Nyxx need internet or Wi-Fi?",
            "No. Nyxx only needs your phone and PC on the same local network. All gamepad data is sent over your local network (UDP) and never reaches the internet. You can even use a phone hotspot with mobile data turned off."
        )
        addQ(
            "What connection modes are available?",
            "Four modes: Wi-Fi (shared router), Hotspot (phone or PC hotspot), Wired (USB tethering), and Bluetooth."
        )
        addQ(
            "Do I need a Wi-Fi router?",
            "No. Any local network works — a Wi-Fi router, a phone/PC hotspot, or a USB cable with USB tethering."
        )
        addQ(
            "How many players can connect?",
            "Up to 8 phones at the same time! Players 1-4 get full PC (Xbox) + Emulator compatibility. Players 5-8 get Emulator-only compatibility (buttons and motion via DSU/Cemuhook, perfect for 8-player emulators)."
        )
        addQ(
            "Will it work with my emulator?",
            "Yes. For emulators that require an Xbox controller (XInput), such as Eden, use Wi-Fi or Wired mode. Bluetooth acts as a generic DInput controller and may not be detected by XInput-only games. You can also launch the emulator through Steam or x360ce."
        )
        addQ(
            "What do I need on the PC?",
            "A Windows PC with the Nyxx Server running and ViGEmBus installed. The server creates the virtual Xbox 360 controller your games see."
        )

        // ---------- HOW TO CONNECT ----------
        addHeader("How to connect")
        addQ(
            "Wi-Fi mode",
            "1) Connect your phone and PC to the same Wi-Fi router.\n2) Start the Nyxx Server on your PC.\n3) On your phone tap Wi-Fi, then scan the QR code on the server (or type the PC IP and port).\nNo internet required."
        )
        addQ(
            "Hotspot mode",
            "1) On your phone, turn on the Mobile Hotspot.\n2) On your PC, connect to that hotspot's Wi-Fi.\n3) Start the Nyxx Server - it shows its hotspot IP.\n4) On your phone tap Hotspot, then scan the QR code or enter the PC IP.\nThis makes a private local network; no internet needed. (Reverse works too: PC hotspot + phone connects.)"
        )
        addQ(
            "Wired (USB) mode",
            "1) Connect your phone to the PC with a USB cable.\n2) Enable USB Tethering on the phone (Settings > Network > Hotspot & tethering).\n3) Start the Nyxx Server - it appears on the PC's tethered network.\n4) Enter the PC's tethered IP on your phone."
        )
        addQ(
            "Bluetooth mode",
            "Nyxx emulates a generic Bluetooth gamepad (DInput). Pair your phone with the PC and make it discoverable when prompted. Note: not detected by XInput-only emulators."
        )

        // ---------- ABOUT / DOCS ----------
        addHeader("About Nyxx")
        addQ(
            "What is Nyxx?",
            "Nyxx turns your Android phone into a fully customizable virtual gamepad for your Windows PC. It streams controller input (buttons, sticks, triggers) plus motion data (gyroscope & accelerometer) over your local network or Bluetooth."
        )
        addQ(
            "Key features",
            "• On-screen touch gamepad with editable layout (resize, spacing, opacity, turbo, analog triggers)\n• Gyro & accelerometer steering\n• Motion / Dance mode\n• Rumble feedback (phone vibrates on in-game rumble)\n• Profiles: Nintendo (Wii U/Switch), Joy-Con L/R, Wii Remote (Vertical), Xbox, PSP\n• Cemuhook motion data for Cemu\n• Up to 8 players"
        )
        addQ(
            "How does it work?",
            "The Android app encodes your controller state into small UDP packets and sends them to the PC server. The server decodes them and feeds a virtual Xbox 360 controller (via ViGEmBus) to your games and emulators."
        )

        // ---------- LINKS & CREDITS ----------
        addHeader("Links & Credits")
        addLink("Source code (GitHub)", "https://github.com/marki19/nyxx-gamepad")
        addText("Developer: geyyb  (GitHub: @marki19)")
        addLink("Buy me a coffee", "https://buymeacoffee.com/geyyb")
    }

    private fun addHeader(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF8A2BE2.toInt())
            letterSpacing = 0.12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(24), 0, dp(8)) }
        }
        container.addView(tv)
    }

    private fun addQ(question: String, answer: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(this@FaqActivity, R.drawable.glass_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }
        val q = TextView(this).apply {
            this.text = question
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val a = TextView(this).apply {
            this.text = answer
            textSize = 13.5f
            setTextColor(0xFFC5C6C7.toInt())
            setLineSpacing(dp(2).toFloat(), 1.1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        }
        card.addView(q)
        card.addView(a)
        container.addView(card)
    }

    private fun addText(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(0xFFC5C6C7.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }
        container.addView(tv)
    }

    private fun addLink(label: String, url: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(this@FaqActivity, R.drawable.btn_primary_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { }
            }
        }
        val tv = TextView(this).apply {
            this.text = label
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val arrow = TextView(this).apply {
            this.text = "↗"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.END
        }
        card.addView(tv)
        card.addView(arrow)
        container.addView(card)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
