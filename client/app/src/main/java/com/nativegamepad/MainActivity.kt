package com.nativegamepad

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import android.app.AlertDialog
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var isGyroEnabled = false
    private var gyroOffsetX = 0f
    private var gyroOffsetY = 0f
    private var smoothedTiltX = 0f
    private var smoothedTiltY = 0f
    private var needsGyroCalibration = false
    private var gyroMode = 0 // 0 = Left X, 1 = Left X+Y, 2 = Right X+Y

    private var udpSender: UdpSender? = null
    private var bluetoothSender: BluetoothHidSender? = null
    private var gamepadView: GamepadView? = null
    private var drawerLayout: DrawerLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        
        val mode = intent?.getStringExtra("CONNECTION_MODE")
        if (mode == "BLUETOOTH") {
            startBluetoothGamepad()
        } else {
            setupHomePage()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setupHomePage() {
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.ipInput)
        val button = findViewById<Button>(R.id.connectButton)
        val btnScanQr = findViewById<Button>(R.id.btnScanQr)

        val prefs = getSharedPreferences("GamepadPrefs", Context.MODE_PRIVATE)
        val autoIp = intent?.getStringExtra("AUTO_CONNECT_IP")
        if (!autoIp.isNullOrBlank()) {
            input.setText(autoIp)
            button.performClick()
        } else {
            input.setText(prefs.getString("last_ip", ""))
        }

        button.setOnClickListener {
            val ipString = input.text.toString().trim()
            if (ipString.isNotBlank()) {
                var ip = ipString
                var port = 5000
                if (ipString.contains(":")) {
                    val parts = ipString.split(":")
                    ip = parts[0]
                    port = parts[1].toIntOrNull() ?: 5000
                }
                
                button.text = "CONNECTING..."
                button.isEnabled = false
                
                prefs.edit().putString("last_ip", ipString).apply()

                thread {
                    val playerIndex = UdpSender.pingServer(ip, port)
                    runOnUiThread {
                        if (playerIndex > 0) {
                            startGamepad(ip, port, playerIndex)
                        } else {
                            button.text = "CONNECT"
                            button.isEnabled = true
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Connection Failed")
                                .setMessage("Could not connect to $ip:$port.\nMake sure the server is running and your PC firewall allows UDP port $port.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }

        findViewById<View>(R.id.btnBackToHome)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            startActivity(intent)
            finish()
        }

        btnScanQr.setOnClickListener {
            val scanner = GmsBarcodeScanning.getClient(this)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue
                    if (rawValue != null) {
                        if (rawValue.startsWith("nyxxpad://")) {
                            val uri = android.net.Uri.parse(rawValue)
                            val ip = uri.getQueryParameter("ip")
                            val port = uri.getQueryParameter("port") ?: "5000"
                            if (!ip.isNullOrBlank()) {
                                input.setText("$ip:$port")
                                button.performClick()
                            }
                        } else {
                            input.setText(rawValue)
                            button.performClick()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
        
        checkForUpdates()
    }

    private fun checkForUpdates() {
        thread {
            try {
                val repoOwner = "marki19"
                val repoName = "nyxx-gamepad"
                val url = java.net.URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "NyxxPadClient-Updater")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                if (connection.responseCode == 200) {
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
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("Update Available")
                                .setMessage("A new version of NyxxPad ($tagName) is available!\n\nWould you like to download it now?")
                                .setPositiveButton("Download") { _, _ ->
                                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(htmlUrl))
                                    startActivity(intent)
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }




    private fun startGamepad(ip: String, port: Int, playerIndex: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        udpSender = UdpSender(ip, port, vibrator)
        udpSender?.onDisconnect = {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Server disconnected", Toast.LENGTH_SHORT).show()
                udpSender?.stop()
                bluetoothSender?.stop()
                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
        udpSender?.start()
        
        setContentView(R.layout.activity_gamepad)
        Toast.makeText(this, "Connected as Player $playerIndex", Toast.LENGTH_LONG).show()
        
        drawerLayout = findViewById(R.id.drawerLayout)
        gamepadView = findViewById(R.id.gamepadView)
        gamepadView?.playerIndex = playerIndex
        gamepadView?.udpSender = udpSender
        
        setupGamepadViewInteractions()
    }

    private fun startBluetoothGamepad() {
        bluetoothSender = BluetoothHidSender(this)
        bluetoothSender?.start()
        
        setContentView(R.layout.activity_gamepad)
        
        drawerLayout = findViewById(R.id.drawerLayout)
        gamepadView = findViewById(R.id.gamepadView)
        gamepadView?.bluetoothSender = bluetoothSender
        
        setupGamepadViewInteractions()
    }

    private fun setupGamepadViewInteractions() {
        val view = gamepadView ?: return
        
        view.onMenuRequested = {
            drawerLayout?.openDrawer(GravityCompat.START)
        }
        
        gamepadView?.onButtonEditRequested = { groupName ->
            showButtonPropertiesDialog(groupName)
        }
        
        gamepadView?.onEditModeExited = {
            findViewById<LinearLayout>(R.id.panelEditProperties)?.visibility = View.GONE
        }
        
        setupSidebar()
        setupDraggablePanel()
    }

    private fun setupDraggablePanel() {
        val panel = findViewById<LinearLayout>(R.id.panelEditProperties) ?: return
        var dX = 0f
        var dY = 0f

        panel.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun showButtonPropertiesDialog(groupName: String) {
        val view = gamepadView ?: return
        val panel = findViewById<LinearLayout>(R.id.panelEditProperties) ?: return
        
        panel.visibility = View.VISIBLE
        
        val tvTitle = panel.findViewById<TextView>(R.id.tvSheetTitle)
        
        val tvSize = panel.findViewById<TextView>(R.id.tvSizeLabel)
        val btnSizeMinus = panel.findViewById<Button>(R.id.btnSizeMinus)
        val btnSizePlus = panel.findViewById<Button>(R.id.btnSizePlus)
        
        val tvSpacing = panel.findViewById<TextView>(R.id.tvSpacingLabel)
        val btnSpacingMinus = panel.findViewById<Button>(R.id.btnSpacingMinus)
        val btnSpacingPlus = panel.findViewById<Button>(R.id.btnSpacingPlus)
        val rowSpacing = panel.findViewById<LinearLayout>(R.id.rowSpacing)
        
        val tvOpacity = panel.findViewById<TextView>(R.id.tvOpacityLabel)
        val btnOpacityMinus = panel.findViewById<Button>(R.id.btnOpacityMinus)
        val btnOpacityPlus = panel.findViewById<Button>(R.id.btnOpacityPlus)
        
        val switchVisible = panel.findViewById<Switch>(R.id.switchVisible)
        val switchTurbo = panel.findViewById<Switch>(R.id.switchTurbo)
        val switchAnalog = panel.findViewById<Switch>(R.id.switchAnalog)

        val btnCancelEdit = panel.findViewById<Button>(R.id.btnCancelEdit)
        val btnSaveEdit = panel.findViewById<Button>(R.id.btnSaveEdit)

        tvTitle?.text = "$groupName PROPERTIES"
        
        val config = view.buttonConfigs[groupName] ?: ButtonConfig()

        fun updateUI() {
            tvSize?.text = "${"%.1f".format(config.scale)}x"
            tvSpacing?.text = "${"%.1f".format(config.spacing)}x"
            tvOpacity?.text = "${(config.opacity * 100).toInt()}%"
            
            if (groupName == "ABXY" || groupName == "DPad") {
                rowSpacing?.visibility = View.VISIBLE
            } else {
                rowSpacing?.visibility = View.INVISIBLE
            }

            switchVisible?.isChecked = config.visible
            switchTurbo?.isChecked = config.turbo
            switchAnalog?.isChecked = config.analogTrigger
            
            if (groupName == "L2" || groupName == "R2") {
                switchAnalog?.visibility = View.VISIBLE
            } else {
                switchAnalog?.visibility = View.GONE
            }
        }
        
        updateUI()

        btnSizeMinus?.setOnClickListener {
            config.scale = (config.scale - 0.1f).coerceAtLeast(0.5f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            updateUI()
        }
        btnSizePlus?.setOnClickListener {
            config.scale = (config.scale + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            updateUI()
        }

        btnSpacingMinus?.setOnClickListener {
            config.spacing = (config.spacing - 0.1f).coerceAtLeast(0.5f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            updateUI()
        }
        btnSpacingPlus?.setOnClickListener {
            config.spacing = (config.spacing + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            updateUI()
        }

        btnOpacityMinus?.setOnClickListener {
            config.opacity = (config.opacity - 0.1f).coerceAtLeast(0.0f)
            view.buttonConfigs[groupName] = config
            view.invalidate()
            updateUI()
        }
        btnOpacityPlus?.setOnClickListener {
            config.opacity = (config.opacity + 0.1f).coerceAtMost(1.0f)
            view.buttonConfigs[groupName] = config
            view.invalidate()
            updateUI()
        }

        switchVisible?.setOnCheckedChangeListener { _, isChecked ->
            config.visible = isChecked
            view.buttonConfigs[groupName] = config
            view.invalidate()
        }

        switchTurbo?.setOnCheckedChangeListener { _, isChecked ->
            config.turbo = isChecked
            view.buttonConfigs[groupName] = config
        }

        switchAnalog?.setOnCheckedChangeListener { _, isChecked ->
            config.analogTrigger = isChecked
            view.buttonConfigs[groupName] = config
        }

        btnSaveEdit?.setOnClickListener {
            panel.visibility = View.GONE
            view.saveEditMode()
        }
        
        btnCancelEdit?.setOnClickListener {
            panel.visibility = View.GONE
            view.cancelEditMode()
        }
    }

    private fun setupSidebar() {
        val view = gamepadView ?: return
        
        val layoutGyroMode = findViewById<LinearLayout>(R.id.layoutGyroMode)
        val spinnerGyroMode = findViewById<Spinner>(R.id.spinnerGyroMode)
        val switchGyro = findViewById<Switch>(R.id.switchGyro)
        val btnCalibrateGyro = findViewById<Button>(R.id.btnCalibrateGyro)
        
        switchGyro?.setOnCheckedChangeListener { _, isChecked ->
            isGyroEnabled = isChecked
            view.isGyroEnabled = isChecked
            layoutGyroMode?.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnCalibrateGyro?.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            if (isChecked) {
                gravitySensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                sensorManager?.unregisterListener(this)
                val state = udpSender?.state ?: bluetoothSender?.state
                if (state != null) {
                    state.lx = 0
                    state.ly = 0
                    state.rx = 0
                    state.ry = 0
                }
            }
        }
        
        val gyroModes = listOf("Left Joystick (X Only)", "Left Joystick (X & Y)", "Right Joystick (X & Y)")
        val gyroAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, gyroModes)
        gyroAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGyroMode?.adapter = gyroAdapter
        spinnerGyroMode?.setSelection(gyroMode)
        
        spinnerGyroMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, viewSelected: View?, position: Int, id: Long) {
                gyroMode = position
                view.gyroTargetMode = position
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        
        findViewById<Button>(R.id.btnCalibrateGyro)?.setOnClickListener {
            needsGyroCalibration = true
            Toast.makeText(this, "Calibrating Gyro...", Toast.LENGTH_SHORT).show()
        }

        val switchRumble = findViewById<Switch>(R.id.switchRumble)
        switchRumble?.setOnCheckedChangeListener { _, isChecked ->
            udpSender?.isRumbleEnabled = isChecked
            bluetoothSender?.isRumbleEnabled = isChecked
        }
        
        // Profiles Setup
        val spinner = findViewById<Spinner>(R.id.spinnerProfile)
        val profiles = listOf(GamepadProfiles.NINTENDO, GamepadProfiles.XBOX, GamepadProfiles.PSP)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        val sharedPrefs = getSharedPreferences("GamepadPrefs", android.content.Context.MODE_PRIVATE)
        val lastProfileId = sharedPrefs.getString("lastProfile", "nintendo")
        
        // Initial Selection
        val initialIndex = profiles.indexOfFirst { it.id == lastProfileId }.coerceAtLeast(0)
        spinner.setSelection(initialIndex)
        
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, viewSelected: View?, position: Int, id: Long) {
                view.setProfile(profiles[position])
                sharedPrefs.edit().putString("lastProfile", profiles[position].id).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        findViewById<Button>(R.id.btnEditLayout).setOnClickListener {
            view.enterEditMode()
            drawerLayout?.closeDrawers()
        }
        
        findViewById<Button>(R.id.btnResetLayout).setOnClickListener {
            view.resetLayoutDefaults()
            setupSidebar() 
        }
        
        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            val mode = intent?.getStringExtra("CONNECTION_MODE")
            if (mode == "BLUETOOTH") {
                bluetoothSender?.stop()
                bluetoothSender = BluetoothHidSender(this@MainActivity)
                bluetoothSender?.start()
                gamepadView?.bluetoothSender = bluetoothSender
            } else {
                udpSender?.stop()
                setupHomePage()
            }
            drawerLayout?.closeDrawers()
        }
        
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            udpSender?.stop()
            bluetoothSender?.stop()
            val intent = Intent(this@MainActivity, HomeActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        udpSender?.stop()
        bluetoothSender?.destroy()
    }

    override fun onResume() {
        super.onResume()
        if (isGyroEnabled) {
            gravitySensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        
        // Zero out gyro inputs to prevent stuck steering when suspended
        val state = udpSender?.state ?: bluetoothSender?.state
        if (state != null) {
            state.lx = 0
            state.ly = 0
            state.rx = 0
            state.ry = 0
        }
    }
    
    override fun onBackPressed() {
        sensorManager?.unregisterListener(this)
        udpSender?.stop()
        bluetoothSender?.stop()
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isGyroEnabled) return
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            if (needsGyroCalibration) {
                gyroOffsetX = event.values[0]
                gyroOffsetY = event.values[1]
                smoothedTiltX = 0f
                smoothedTiltY = 0f
                needsGyroCalibration = false
                runOnUiThread { 
                    gamepadView?.triggerCalibrationFlash()
                    Toast.makeText(this@MainActivity, "Gyro Calibrated!", Toast.LENGTH_SHORT).show() 
                }
            }

            val rawTiltX = event.values[0] - gyroOffsetX
            val rawTiltY = event.values[1] - gyroOffsetY
            
            // Low-pass filter for smoothing to prevent jitter
            smoothedTiltX += 0.2f * (rawTiltX - smoothedTiltX)
            smoothedTiltY += 0.2f * (rawTiltY - smoothedTiltY)
            
            val deadzone = 0.5f // About 3 degrees of gravity tilt deadzone
            var outX = 0f
            var outY = 0f
            
            // X-axis (Pitch / Forward-Backward)
            if (Math.abs(smoothedTiltX) > deadzone) {
                val magnitude = (Math.abs(smoothedTiltX) - deadzone) / (6f - deadzone)
                outX = Math.signum(smoothedTiltX) * magnitude.coerceIn(0f, 1f)
                outX = outX * outX * Math.signum(outX)
            }
            
            // Y-axis (Roll / Left-Right)
            if (Math.abs(smoothedTiltY) > deadzone) {
                val magnitude = (Math.abs(smoothedTiltY) - deadzone) / (6f - deadzone)
                outY = Math.signum(smoothedTiltY) * magnitude.coerceIn(0f, 1f)
                outY = outY * outY * Math.signum(outY)
            }
            
            val lx = (outY * 32767).toInt().toShort() // Left/Right steering mapped to Y tilt
            val ly = (-outX * 32767).toInt().toShort() // Up/Down pitch mapped to X tilt (inverted for standard joystick feel)
            
            // Only update if not paused!
            if (gamepadView?.isInputPaused == false) {
                val state = udpSender?.state ?: bluetoothSender?.state
                if (state != null) {
                    if (gyroMode == 0) {
                        state.lx = lx
                    } else if (gyroMode == 1) {
                        state.lx = lx
                        state.ly = ly
                    } else if (gyroMode == 2) {
                        state.rx = lx
                        state.ry = ly
                    }
                }
            }
            
            // Push visual tilt to GamepadView
            gamepadView?.gyroTiltX = outY
            gamepadView?.gyroTiltY = -outX
            gamepadView?.postInvalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
