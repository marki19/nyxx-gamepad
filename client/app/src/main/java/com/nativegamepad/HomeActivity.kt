package com.nativegamepad

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import kotlin.concurrent.thread

@Suppress("SpellCheckingInspection")
@SuppressLint("MissingPermission", "SetTextI18n")
class HomeActivity : AppCompatActivity() {

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var btAdapter: BluetoothAdapter? = null

    private val enableBtLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            launchArduinoMode(null)
        } else {
            Toast.makeText(this, "Please enable Bluetooth to control the car", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpdateButtonState(isUpdateAvailable: Boolean) {
        val tvUpdateText = findViewById<TextView>(R.id.tvUpdateText)
        val ivUpdateIcon = findViewById<ImageView>(R.id.ivUpdateIcon)
        if (isUpdateAvailable) {
            tvUpdateText.text = "UPDATE AVAILABLE"
            tvUpdateText.setTextColor("#4CAF50".toColorInt())
            tvUpdateText.setTypeface(null, Typeface.BOLD)
            ivUpdateIcon.setImageResource(R.drawable.ic_new_releases)
            ivUpdateIcon.backgroundTintList = ColorStateList.valueOf("#4CAF50".toColorInt())
            ivUpdateIcon.imageTintList = ColorStateList.valueOf("#121212".toColorInt())
        } else {
            tvUpdateText.text = "CHECK UPDATE"
            tvUpdateText.setTextColor("#C5C6C7".toColorInt())
            tvUpdateText.setTypeface(null, Typeface.NORMAL)
            ivUpdateIcon.setImageResource(R.drawable.ic_update)
            ivUpdateIcon.backgroundTintList = ColorStateList.valueOf("#2C2C2C".toColorInt())
            ivUpdateIcon.imageTintList = ColorStateList.valueOf("#C5C6C7".toColorInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeConfig.load(this)
        setContentView(R.layout.activity_home)
        applyTheme()

        findViewById<TextView>(R.id.tvAppVersion).text = "v${BuildConfig.VERSION_NAME}"

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
        val localVersion = "v${BuildConfig.VERSION_NAME}"
        val lastKnownVersion = prefs.getString("last_known_version", "")

        if (lastKnownVersion != localVersion) {
            prefs.edit {
                putString("last_known_version", localVersion)
                remove("update_url")
                remove("update_tag")
                putLong("last_update_check", 0L)
            }
        }

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
                    .setMessage("A new version ($tagName) is available!\n\nWould you like to download it now?")
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
            prefs.edit { putLong("last_update_check", now) }
            checkForUpdates(manual = false)
        }

        val wifiButton = findViewById<Button>(R.id.btnConnectWifi)
        wifiButton.setOnClickListener { showWifiOneTimeTip() }

        findViewById<Button>(R.id.btnConnectUsb).setOnClickListener {
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
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (adapter == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val savedMac = getSharedPreferences("robot_car_prefs", Context.MODE_PRIVATE).getString("last_car_mac", null)
            if (!adapter.isEnabled) {
                try {
                    @Suppress("DEPRECATION")
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBtLauncher.launch(enableBtIntent)
                } catch (_: Exception) {
                    launchArduinoMode(savedMac)
                }
            } else {
                launchArduinoMode(savedMac)
            }
        }
    }

    private fun showBluetoothWarningDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bluetooth (Arduino RC Mode)")
            .setMessage(
                "⚠️ IMPORTANT CONNECTION DETAILS:\n\n" +
                "• Purpose: This mode connects directly to Arduino / ESP32 microcontrollers with HC-05 / HC-06 Bluetooth SPP modules.\n\n" +
                "• NOT FOR PC / EMULATORS: This mode will NOT connect to the Nyxx PC Server or games/emulators. For PC gaming, please use Wi-Fi or USB Tethering mode.\n\n" +
                "• Supported Devices: Only Bluetooth Serial (SPP) modules can be controlled. Audio devices (headphones, speakers), smartwatches, and PCs are not supported.\n\n" +
                "• Serial Commands: Controls transmit character commands ('F' = Forward, 'B' = Backward, 'L' = Left, 'R' = Right, 'S' = Stop).\n\n" +
                "• Pairing PIN: Standard HC-05 PIN is usually 1234 or 0000."
            )
            .setPositiveButton("Proceed to Scan") { _, _ ->
                showArduinoDeviceScanner()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDeviceLabel(device: BluetoothDevice, isPaired: Boolean): String {
        val name = device.name ?: "Unknown Device"
        val isArduinoHint = name.contains("HC-05", ignoreCase = true) ||
                           name.contains("HC-06", ignoreCase = true) ||
                           name.contains("BT05", ignoreCase = true) ||
                           name.contains("JDY", ignoreCase = true) ||
                           name.contains("SPP", ignoreCase = true) ||
                           name.contains("ARDUINO", ignoreCase = true) ||
                           name.contains("ROBOT", ignoreCase = true) ||
                           name.contains("LINVOR", ignoreCase = true)
        val tag = if (isArduinoHint) " [Arduino/SPP]" else ""
        val status = if (isPaired) "(Paired)" else "(Unpaired)"
        return "$name$tag $status"
    }

    private fun showArduinoDeviceScanner() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = manager.adapter
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (btAdapter?.isEnabled == false) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceList = mutableListOf<BluetoothDevice>()
        val displayList = mutableListOf<String>()
        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Arduino (HC-05/HC-06)")
            .setAdapter(arrayAdapter) { _, which ->
                val device = deviceList[which]
                btAdapter?.cancelDiscovery()

                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Toast.makeText(this, "Pairing with ${device.name ?: "module"}... Enter PIN (1234/0000) if prompted", Toast.LENGTH_LONG).show()
                    device.createBond()
                    // The receiver will handle the rest once bonded
                } else {
                    launchArduinoMode(device.address)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                btAdapter?.cancelDiscovery()
            }
            .setOnDismissListener {
                btAdapter?.cancelDiscovery()
            }
            .create()

        dialog.show()

        // Populate already paired devices first
        btAdapter?.bondedDevices?.forEach {
            deviceList.add(it)
            displayList.add(formatDeviceLabel(it, isPaired = true))
        }
        arrayAdapter.notifyDataSetChanged()

        // Set up scanner for new devices
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && device.name != null && !deviceList.any { it.address == device.address }) {
                            deviceList.add(device)
                            displayList.add(formatDeviceLabel(device, isPaired = false))
                            arrayAdapter.notifyDataSetChanged()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (state == BluetoothDevice.BOND_BONDED && device != null) {
                            Toast.makeText(this@HomeActivity, "Paired successfully with ${device.name}!", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            launchArduinoMode(device.address)
                        } else if (state == BluetoothDevice.BOND_NONE) {
                            Toast.makeText(this@HomeActivity, "Pairing failed or cancelled", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        btAdapter?.startDiscovery()
    }

    private fun launchArduinoMode(macAddress: String?) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("CONNECTION_MODE", "ARDUINO")
        if (!macAddress.isNullOrBlank()) {
            intent.putExtra("MAC_ADDRESS", macAddress)
        }
        startActivity(intent)
        finish()
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
                prefs.edit { putBoolean("wifi_tip_shown", true) }
                startWifi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (manual) {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
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

                    val localVersion = "v${BuildConfig.VERSION_NAME}"

                    if (tagName.isNotEmpty() && tagName != localVersion) {
                        val prefs = getSharedPreferences("GamepadPrefs", MODE_PRIVATE)
                        prefs.edit {
                            putString("update_url", htmlUrl)
                            putString("update_tag", tagName)
                        }

                        runOnUiThread {
                            setUpdateButtonState(true)
                            if (manual) {
                                AlertDialog.Builder(this@HomeActivity)
                                    .setTitle("Update Available")
                                    .setMessage("A new version ($tagName) is available!\n\nWould you like to download it now?")
                                    .setPositiveButton("Download") { _, _ ->
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    } else {
                        getSharedPreferences("GamepadPrefs", MODE_PRIVATE).edit {
                            remove("update_url")
                            remove("update_tag")
                        }
                        runOnUiThread {
                            setUpdateButtonState(false)
                            if (manual) {
                                AlertDialog.Builder(this@HomeActivity)
                                    .setTitle("Up to Date")
                                    .setMessage("You are already running the latest version ($localVersion).")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                } else if (manual) {
                    runOnUiThread {
                        Toast.makeText(this@HomeActivity, "Failed to check for updates.", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                if (manual) {
                    runOnUiThread {
                        Toast.makeText(this@HomeActivity, "Error checking for updates.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun applyTheme() {
        val rootLayout = findViewById<ConstraintLayout>(R.id.rootLayout)
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
        tvFaqIcon?.backgroundTintList = ColorStateList.valueOf(pColor)
        tvThemeIcon?.backgroundTintList = ColorStateList.valueOf(pColor)

        // Button Drawables Tint
        val buttons = listOf(btnWifi, btnUsb, btnBluetooth)
        for (btn in buttons) {
            btn?.compoundDrawablesRelative?.get(0)?.setTint(pColor)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
                // Ignore if not registered
            }
        }
        btAdapter?.cancelDiscovery()
    }
}
