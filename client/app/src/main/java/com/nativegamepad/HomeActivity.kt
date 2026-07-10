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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class HomeActivity : AppCompatActivity() {

    private val discoverabilityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("CONNECTION_MODE", "BLUETOOTH")
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

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

        val wifiButton = findViewById<Button>(R.id.btnConnectWifi)
        wifiButton.setOnClickListener { showWifiOneTimeTip() }

        findViewById<Button>(R.id.btnConnectHotspot).setOnClickListener {
            showHotspotInstructions()
        }

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
            .setTitle("Wi-Fi Mode")
            .setMessage("Connect your phone and PC to the SAME Wi-Fi network.\n\nThen start the Nyxx Server on your PC and scan the QR code (or type the PC's IP and port).\n\nNo internet connection is required — only a shared local network.")
            .setPositiveButton("Got it") { _, _ ->
                prefs.edit().putBoolean("wifi_tip_shown", true).apply()
                startWifi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Hotspot mode: explain how to create a private local network, then connect.
    private fun showHotspotInstructions() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Hotspot Mode")
            .setMessage("1. On your phone, turn on the Mobile Hotspot.\n2. On your PC, connect to that hotspot's Wi-Fi.\n3. Start the Nyxx Server on your PC — it will show its hotspot IP.\n4. Tap 'Continue' below, then scan the QR code or enter the PC IP.\n\nThis creates a private local network. No internet is needed. (You can also use a PC hotspot with your phone connected instead.)")
            .setPositiveButton("Continue to Connect") { _, _ ->
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("CONNECTION_MODE", "WIFI")
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
