package com.nativegamepad

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    private val discoverabilityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Whether they pressed Allow or Deny, we still want to launch the Gamepad,
        // but if they pressed Allow, it will be discoverable.
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("CONNECTION_MODE", "BLUETOOTH")
        startActivity(intent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val intentData = intent?.data
        if (intentData != null && intentData.scheme == "nyxxpad" && intentData.host == "connect") {
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

        findViewById<Button>(R.id.btnConnectWifi).setOnClickListener {
            // Standard Wi-Fi UDP connection
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("CONNECTION_MODE", "WIFI")
            startActivity(intent)
            finish()
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
}
