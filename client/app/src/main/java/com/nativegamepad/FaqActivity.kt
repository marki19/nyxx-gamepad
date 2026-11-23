package com.nativegamepad

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat

@Suppress("SpellCheckingInspection")
class FaqActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeConfig.load(this)
        setContentView(R.layout.activity_faq)

        val btnBack = findViewById<ImageView>(R.id.btnFaqBack)
        btnBack.setOnClickListener { finish() }
        btnBack.setColorFilter(ThemeConfig.primaryColor)
        
        val rootLayout = findViewById<ConstraintLayout>(R.id.rootLayout)
        rootLayout?.setBackgroundColor(ThemeConfig.backgroundColor)
            ?: window.decorView.setBackgroundColor(ThemeConfig.backgroundColor)
        
        findViewById<TextView>(R.id.tvFaqTitle)?.setTextColor(ThemeConfig.primaryColor)
        
        container = findViewById(R.id.faqContainer)

        buildContent()
    }

    private fun buildContent() {
        // ---------- GENERAL ----------
        addHeader("General")
        addQ(
            "What connection modes are available?",
            "Nyxx supports three dedicated modes:\n\n" +
            "1. Wi-Fi / Hotspot (Local Network): Connects wirelessly to the Nyxx PC Server for PC gaming and emulators.\n" +
            "2. Wired (USB Tethering): Ultra-low latency wired connection to the Nyxx PC Server.\n" +
            "3. Bluetooth (Arduino / RC Mode): Direct Serial Port Profile (SPP) connection to Arduino, ESP32, and DIY robot cars using HC-05 / HC-06 modules."
        )
        addQ(
            "Does Nyxx need an active internet connection?",
            "No. All gamepad and motion data is transmitted strictly over your local network (UDP) or direct Bluetooth (RFCOMM/SPP). No internet connection or mobile data is needed."
        )
        addQ(
            "Will Bluetooth mode work with PC games or emulators?",
            "No. Bluetooth mode is strictly a Direct-to-Arduino Serial (SPP) controller designed for robotics and DIY hardware. For PC games and emulators (Cemu, Dolphin, Eden, Yuzu, etc.), always use Wi-Fi or Wired (USB) mode to connect to the Nyxx PC Server."
        )
        addQ(
            "How many players can connect to PC?",
            "Up to 8 phones simultaneously! Players 1-4 get full Xbox 360 controller emulation (ViGEmBus) + Cemuhook motion. Players 5-8 get direct DSU Cemuhook emulation (buttons + motion) for 8-player party emulators."
        )
        addQ(
            "What do I need on the PC?",
            "A Windows PC running the Nyxx Server application with the ViGEmBus driver installed."
        )

        // ---------- HOW TO CONNECT ----------
        addHeader("How to Connect")
        addQ(
            "Wi-Fi / Hotspot Mode (PC Gaming)",
            "1) Connect your phone and PC to the same Wi-Fi router, phone hotspot, or PC hotspot.\n" +
            "2) Start the Nyxx Server on your PC.\n" +
            "3) Tap 'Wi-Fi / Hotspot' on your phone. Nyxx will automatically discover and display your PC server.\n" +
            "4) Tap your server to connect immediately."
        )
        addQ(
            "Wired (USB Tethering) Mode (PC Gaming)",
            "1) Connect your phone to your PC via USB cable.\n" +
            "2) In Android Settings > Network > Hotspot & Tethering, turn ON 'USB Tethering'.\n" +
            "3) Start the Nyxx Server on your PC.\n" +
            "4) Tap 'Wired (USB Tethering)' on your phone and enter the PC's tethered IP."
        )
        addQ(
            "Bluetooth (Arduino / RC Mode)",
            "1) Power on your Arduino or ESP32 with an HC-05 / HC-06 module connected.\n" +
            "2) Tap 'Bluetooth (Arduino / RC Mode)' on the home screen and read the connection details.\n" +
            "3) Select your paired HC-05 module or tap an unpaired module to pair (default PIN is usually '1234' or '0000').\n" +
            "4) Once connected, the gamepad opens and streams ASCII command characters to the Arduino."
        )

        // ---------- ARDUINO & RC HARDWARE ----------
        addHeader("Arduino & Hardware Control")
        addQ(
            "What commands does Arduino mode send?",
            "Nyxx sends simple ASCII character bytes over Bluetooth Serial (SPP):\n\n" +
            "• 'F' = Forward (D-Pad Up / Analog Pitch Up)\n" +
            "• 'B' = Backward (D-Pad Down / Analog Pitch Down)\n" +
            "• 'L' = Left (D-Pad Left / Analog Roll Left)\n" +
            "• 'R' = Right (D-Pad Right / Analog Roll Right)\n" +
            "• 'S' = Stop (Released state / Centered)\n\n" +
            "Transmission is throttled to 20Hz to prevent buffer overflows on microcontrollers."
        )
        addQ(
            "Why can't I control headphones or smartwatches in Bluetooth mode?",
            "Bluetooth mode uses RFCOMM Serial Port Profile (SPP) specifically designed for microcontrollers and serial modems. Audio devices (A2DP/HFP) and consumer wearables do not accept serial control streams."
        )
        addQ(
            "Arduino Wiring & Troubleshooting Tips",
            "• Wiring: Connect HC-05 TX to Arduino RX (Pin 0 / SoftwareSerial RX), and HC-05 RX to Arduino TX (use a 1k/2k resistor voltage divider for 3.3V RX logic if needed).\n" +
            "• Baud Rate: Ensure your Arduino `Serial.begin(...)` matches your HC-05 default baud rate (usually 9600 or 38400).\n" +
            "• Pairing: If pairing fails, unpair the HC-05 in Android Bluetooth Settings and pair freshly in Nyxx with PIN 1234 or 0000."
        )

        // ---------- ABOUT NYXX ----------
        addHeader("About Nyxx")
        addQ(
            "Key Features",
            "• Customizable touch gamepad layout (resize, move, opacity, turbo, analog triggers)\n" +
            "• Precise Gyroscope & Accelerometer steering with deadzone and calibration\n" +
            "• Motion / Dance mode for motion-controlled party games\n" +
            "• Dual-motor Haptic Rumble feedback\n" +
            "• Controller profiles: Nintendo Pro, Joy-Con (L/R), Wii Remote, Xbox 360, PSP\n" +
            "• Direct-to-Arduino Bluetooth RC car & robotics control\n" +
            "• Up to 8 simultaneous wireless players"
        )

        // ---------- LINKS & CREDITS ----------
        addHeader("Links & Credits")
        addLink("Source Code & Releases (GitHub)", "https://github.com/marki19/nyxx-gamepad")
        addText("Developer: geyyb  (GitHub: @marki19)")
    }

    private fun addHeader(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ThemeConfig.primaryColor)
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
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
            setTypeface(null, Typeface.BOLD)
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
            background = ContextCompat.getDrawable(this@FaqActivity, R.drawable.btn_premium_card)
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
            setTypeface(null, Typeface.BOLD)
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
