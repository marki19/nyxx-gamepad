package com.nativegamepad

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import android.net.Uri
import android.widget.TextView

class HomeActivity : AppCompatActivity() {



    private val discoverabilityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("CONNECTION_MODE", "BLUETOOTH")
            startActivity(intent)
            finish()
        }
    }

    private fun setUpdateButtonState(isUpdateAvailable: Boolean) {
        val tvUpdateText = findViewById<android.widget.TextView>(R.id.tvUpdateText)
        val ivUpdateIcon = findViewById<android.widget.ImageView>(R.id.ivUpdateIcon)
        if (isUpdateAvailable) {
            tvUpdateText.text = "UPDATE AVAILABLE"
            tvUpdateText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            tvUpdateText.setTypeface(null, android.graphics.Typeface.BOLD)
            ivUpdateIcon.setImageResource(R.drawable.ic_new_releases)
            ivUpdateIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
            ivUpdateIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#121212"))
        } else {
            tvUpdateText.text = "CHECK UPDATE"
            tvUpdateText.setTextColor(android.graphics.Color.parseColor("#C5C6C7"))
            tvUpdateText.setTypeface(null, android.graphics.Typeface.NORMAL)
            ivUpdateIcon.setImageResource(R.drawable.ic_update)
            ivUpdateIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2C2C2C"))
            ivUpdateIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#C5C6C7"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeConfig.load(this)
        setContentView(R.layout.activity_home)
        applyTheme()

        findViewById<TextView>(R.id.tvAppVersion).text = "v" + BuildConfig.VERSION_NAME

        val intentData = intent?.data
        if (intentData != null && intentData.scheme == "Nyxx" && intentData.host == "connect") {
            val ip = intentData.getQueryParameter("ip")
            val port = intentData.getQueryParameter("port") ?: "5000"
            if (!ip.isNullOrBlank()) {
                val connectIntent = Intent(this, MainActivity::class.java)
                connectIntent.putExtra("CONNECTION_MODE", "WIFI")
                connectIntent.putExtra("AUTO_CONNECT_IP", "$ip:$port")
                startActivity(connectIntent)
                finish()
                return
            }
        }

        // FAQ (?) button - opens FAQ & Documentation
        findViewById<View>(R.id.faqButton).setOnClickListener {
            startActivity(Intent(this, FaqActivity::class.java))
        }

        // Theme Settings button
        findViewById<View>(R.id.btnThemeSettings).setOnClickListener {
            ThemeSettingsDialog(this) {
                applyTheme()
            }.show()
        }

        val prefs = getSharedPreferences("GamepadPrefs", MODE_PRIVATE)
        val updateUrl = prefs.getString("update_url", "")
        val btnUpdate = findViewById<View>(R.id.btnUpdate)
        
        setUpdateButtonState(!updateUrl.isNullOrEmpty())
        
        btnUpdate.setOnClickListener {
            if (prefs.getString("update_url", "").isNullOrEmpty()) {
                checkForUpdates(manual = true)
            } else {
                val tagName = prefs.getString("update_tag", "")
                AlertDialog.Builder(this)
                    .setTitle("Update Available")
                    .setMessage("A new version of Nyxx ($tagName) is available!\n\nWould you like to download it now?")
                    .setPositiveButton("Download") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.getString("update_url", ""))))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        val lastCheck = prefs.getLong("last_update_check", 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck > 12 * 60 * 60 * 1000L) { // 12 hours
            prefs.edit().putLong("last_update_check", now).apply()
            checkForUpdates(manual = false)
        }

        val wifiButton = findViewById<Button>(R.id.btnConnectWifi)
        wifiButton.setOnClickListener { showWifiOneTimeTip() }

        findViewById<Button>(R.id.btnConnectUsb).setOnClickListener {
            // Explain USB Tethering
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("USB Tethering Mode")
                .setMessage("1. Connect your phone to your PC via USB cable.\n2. Go to Android Settings > Network > Hotspot & Tethering, and enable 'USB Tethering'.\n3. Run the PC Server.\n4. Enter the PC's new tethered IP address on the next screen.")
                .setPositiveButton("Proceed") { _, _ ->
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("CONNECTION_MODE", "USB")
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.btnConnectBluetooth).setOnClickListener {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Bluetooth Emulator Warning")
                .setMessage("Native Bluetooth acts as a generic DInput controller.\n\nModern emulators requiring an Xbox Controller (XInput), such as Eden, will NOT detect it.\n\nIf your emulator strictly requires an Xbox controller, please use Wi-Fi or Wired mode instead, or run the emulator through Steam or x360ce.")
                .setPositiveButton("Continue Anyway") { _, _ ->
                    val discoverableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    }
                    discoverabilityLauncher.launch(discoverableIntent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startWifi() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("CONNECTION_MODE", "WIFI")
        startActivity(intent)
        finish()
    }

    // Wi-Fi mode: show a one-time setup instruction, then proceed.
    private fun showWifiOneTimeTip() {
        val prefs = getSharedPreferences("GamepadPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("wifi_tip_shown", false)) {
            startWifi()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Wi-Fi / Hotspot Mode")
            .setMessage("Connect your phone and PC to the SAME local network (Wi-Fi router or phone/PC hotspot).\n\nThen start the Nyxx Server on your PC. The app will automatically scan and detect the server, or you can enter the PC's IP address manually.")
            .setPositiveButton("Got it") { _, _ ->
                prefs.edit().putBoolean("wifi_tip_shown", true).apply()
                startWifi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (manual) {
            android.widget.Toast.makeText(this, "Checking for updates...", android.widget.Toast.LENGTH_SHORT).show()
        }
        thread {
            try {
                val repoOwner = "marki19"
                val repoName = "nyxx-gamepad"
                val url = java.net.URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    val jsonResponse = org.json.JSONObject(response.toString())
                    val tagName = jsonResponse.optString("tag_name", "")
                    val htmlUrl = jsonResponse.optString("html_url", "")
                    
                    val localVersion = "v" + BuildConfig.VERSION_NAME
                    
                    if (tagName.isNotEmpty() && tagName != localVersion) {
                        val prefs = getSharedPreferences("GamepadPrefs", MODE_PRIVATE)
                        prefs.edit()
                            .putString("update_url", htmlUrl)
                            .putString("update_tag", tagName)
                            .apply()
                            
                        runOnUiThread {
                            setUpdateButtonState(true)
                            if (manual) {
                                AlertDialog.Builder(this@HomeActivity)
                                    .setTitle("Update Available")
                                    .setMessage("A new version of Nyxx ($tagName) is available!\n\nWould you like to download it now?")
                                    .setPositiveButton("Download") { _, _ ->
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    } else {
                        getSharedPreferences("GamepadPrefs", MODE_PRIVATE).edit()
                            .remove("update_url")
                            .remove("update_tag")
                            .apply()
                        runOnUiThread {
                            setUpdateButtonState(false)
                            if (manual) {
                                AlertDialog.Builder(this@HomeActivity)
                                    .setTitle("Up to Date")
                                    .setMessage("You are already running the latest version of Nyxx ($localVersion).")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                } else if (manual) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this@HomeActivity, "Failed to check for updates.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                if (manual) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this@HomeActivity, "Error checking for updates.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun applyTheme() {
        val rootLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rootLayout)
        if (rootLayout == null) {
            window.decorView.setBackgroundColor(ThemeConfig.backgroundColor)
        } else {
            rootLayout.setBackgroundColor(ThemeConfig.backgroundColor)
        }
        val tvNyxx = findViewById<TextView>(R.id.tvNyxx)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
        val tvFaqIcon = findViewById<TextView>(R.id.tvFaqIcon)
        val tvThemeIcon = findViewById<TextView>(R.id.tvThemeIcon)
        val btnWifi = findViewById<Button>(R.id.btnConnectWifi)
        val btnUsb = findViewById<Button>(R.id.btnConnectUsb)
        val btnBluetooth = findViewById<Button>(R.id.btnConnectBluetooth)

        val pColor = ThemeConfig.primaryColor
        
        // Brand text shadow color & subtitle color
        tvNyxx?.setShadowLayer(12f, 0f, 4f, pColor)
        tvSubtitle?.setTextColor(pColor)
        
        // FAQ and Theme circle backgrounds
        tvFaqIcon?.backgroundTintList = android.content.res.ColorStateList.valueOf(pColor)
        tvThemeIcon?.backgroundTintList = android.content.res.ColorStateList.valueOf(pColor)
        
        // Button Drawables Tint
        val buttons = listOf(btnWifi, btnUsb, btnBluetooth)
        for (btn in buttons) {
            btn?.compoundDrawablesRelative?.get(0)?.setTint(pColor)
        }
    }
}
