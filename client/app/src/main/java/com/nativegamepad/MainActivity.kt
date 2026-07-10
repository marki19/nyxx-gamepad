package com.nativegamepad

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.OnBackPressedCallback
import android.os.Bundle
import android.os.BatteryManager
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
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
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.drawerlayout.widget.DrawerLayout
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SensorEventListener {

    private var discoveryJob: Job? = null

    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var isGyroEnabled = false
    private var isDanceModeEnabled = false
    private var gyroOffsetX = 0f
    private var gyroOffsetY = 0f
    private var smoothedTiltX = 0f
    private var smoothedTiltY = 0f
    private var needsGyroCalibration = false
    private var calibrationFramesAccel = 0
    private var calibrationFramesGyro = 0
    private var sumAx = 0.0; private var sumAy = 0.0; private var sumAz = 0.0
    private var sumGx = 0.0; private var sumGy = 0.0; private var sumGz = 0.0
    private var gyroBiasX = 0.0; private var gyroBiasY = 0.0; private var gyroBiasZ = 0.0
    private var gyroMode = 0 // 0 = Left X, 1 = Left X+Y, 2 = Right X+Y

    private var udpSender: UdpSender? = null
    private var bluetoothSender: BluetoothHidSender? = null
    private var gamepadView: GamepadView? = null
    private var drawerLayout: DrawerLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemBars()
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = findViewById<LinearLayout>(R.id.panelEditProperties)
                if (panel?.visibility == View.VISIBLE) {
                    panel.visibility = View.GONE
                    gamepadView?.saveEditMode()
                    return
                }
                val drawer = drawerLayout
                if (drawer?.isDrawerOpen(GravityCompat.START) == true) {
                    drawer.closeDrawer(GravityCompat.START)
                    return
                }
                sensorManager?.unregisterListener(this@MainActivity)
                udpSender?.stop()
                bluetoothSender?.stop()
                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                startActivity(intent)
                finish()
            }
        })
        
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

    @android.annotation.SuppressLint("SetTextI18n")
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
            
            // Start UDP Auto-Discovery scanner
            discoveryJob?.cancel()
            discoveryJob = lifecycleScope.launch(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.broadcast = true
                    socket.soTimeout = 2000
                    
                    val broadcastAddress = InetAddress.getByName("255.255.255.255")
                    val sendData = "Nyxx_DISCOVER".toByteArray()
                    val receiveData = ByteArray(1024)
                    
                    while (isActive) {
                        try {
                            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddress, 55555)
                            socket.send(sendPacket)
                            
                            val receivePacket = DatagramPacket(receiveData, receiveData.size)
                            socket.receive(receivePacket)
                            val message = String(receivePacket.data, 0, receivePacket.length)
                            
                            if (message.startsWith("Nyxx_SERVER:")) {
                                val port = message.substringAfter("Nyxx_SERVER:")
                                val ip = receivePacket.address.hostAddress
                                
                                runOnUiThread {
                                    input.setText(getString(R.string.ip_port_format, ip, port.toString()))
                                }
                                break
                            }
                            
                            kotlinx.coroutines.delay(1000)
                        } catch (_: java.net.SocketTimeoutException) {
                            // Ignore timeout
                        } catch (e: Exception) {
                            e.printStackTrace()
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    socket?.close()
                }
            }
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
                
                prefs.edit { putString("last_ip", ipString) }

                thread {
                    val playerIndex = UdpSender.pingServer(ip, port)
                    runOnUiThread {
                        if (playerIndex > 0) {
                            startGamepad(ip, port, playerIndex)
                        } else {
                            button.text = "CONNECT"
                            button.isEnabled = true
                            val title: String
                            val message: String
                            if (playerIndex == 0) {
                                title = "Server Full"
                                message = "The server at $ip:$port already has 8 players connected.\n\nPlease wait for a player to disconnect and try again."
                            } else {
                                title = "Connection Failed"
                                message = "Could not connect to $ip:$port.\nMake sure the server is running and your PC firewall allows UDP port $port."
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(title)
                                .setMessage(message)
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
                        if (rawValue.startsWith("Nyxx://")) {
                            val uri = rawValue.toUri()
                            val ip = uri.getQueryParameter("ip")
                            val port = uri.getQueryParameter("port") ?: "5000"
                            if (!ip.isNullOrBlank()) {
                                runOnUiThread {
                                    input.setText(getString(R.string.ip_port_format, ip, port))
                                }
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
                connection.setRequestProperty("User-Agent", "NyxxClient-Updater")
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
                                .setMessage("A new version of Nyxx ($tagName) is available!\n\nWould you like to download it now?")
                                .setPositiveButton("Download") { _, _ ->
                                    val intent = Intent(Intent.ACTION_VIEW, htmlUrl.toUri())
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
        val vibrator = getSystemService(android.os.Vibrator::class.java)
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
        udpSender?.onServerFull = {
            runOnUiThread {
                udpSender?.stop()
                Toast.makeText(this@MainActivity, "Server is full (4 players max).", Toast.LENGTH_LONG).show()
                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
        udpSender?.start()
        updateBatteryLevel()

        setContentView(R.layout.activity_gamepad)
        Toast.makeText(this, "Connected as Player $playerIndex", Toast.LENGTH_LONG).show()
        
        drawerLayout = findViewById(R.id.drawerLayout)
        gamepadView = findViewById(R.id.gamepadView)
        gamepadView?.playerIndex = playerIndex
        gamepadView?.udpSender = udpSender
        
        setupGamepadViewInteractions()
    }

    @android.annotation.SuppressLint("SetTextI18n")
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
                MotionEvent.ACTION_UP -> {
                    view.performClick()
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
        
        val switchVisible = panel.findViewById<SwitchCompat>(R.id.switchVisible)
        val switchTurbo = panel.findViewById<SwitchCompat>(R.id.switchTurbo)
        val switchAnalog = panel.findViewById<SwitchCompat>(R.id.switchAnalog)

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
            view.invalidate()
            view.saveConfigState()
            updateUI()
        }
        btnSizePlus?.setOnClickListener {
            config.scale = (config.scale + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            view.invalidate()
            view.saveConfigState()
            updateUI()
        }

        btnSpacingMinus?.setOnClickListener {
            config.spacing = (config.spacing - 0.1f).coerceAtLeast(0.5f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            view.invalidate()
            view.saveConfigState()
            updateUI()
        }
        btnSpacingPlus?.setOnClickListener {
            config.spacing = (config.spacing + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config
            view.applyScales()
            view.invalidate()
            view.saveConfigState()
            updateUI()
        }

        btnOpacityMinus?.setOnClickListener {
            config.opacity = (config.opacity - 0.1f).coerceAtLeast(0.1f)
            view.buttonConfigs[groupName] = config
            view.saveConfigState()
            view.invalidate()
            updateUI()
        }
        btnOpacityPlus?.setOnClickListener {
            config.opacity = (config.opacity + 0.1f).coerceAtMost(1.0f)
            view.buttonConfigs[groupName] = config
            view.saveConfigState()
            view.invalidate()
            updateUI()
        }

        switchVisible?.setOnCheckedChangeListener { _, isChecked ->
            config.visible = isChecked
            view.buttonConfigs[groupName] = config
            view.saveConfigState()
            view.invalidate()
        }

        switchTurbo?.setOnCheckedChangeListener { _, isChecked ->
            config.turbo = isChecked
            view.buttonConfigs[groupName] = config
            view.saveConfigState()
        }

        switchAnalog?.setOnCheckedChangeListener { _, isChecked ->
            config.analogTrigger = isChecked
            view.buttonConfigs[groupName] = config
            view.saveConfigState()
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
        val switchGyro = findViewById<SwitchCompat>(R.id.switchGyro)
        val switchDanceMode = findViewById<SwitchCompat>(R.id.switchDanceMode)
        val btnCalibrateSensors = findViewById<Button>(R.id.btnCalibrateSensors)
        
        switchGyro?.setOnCheckedChangeListener { _, isChecked ->
            // Fix 7: Warn if gyro is toggled in Bluetooth mode
            if (isChecked && bluetoothSender != null) {
                Toast.makeText(this, "Gyro steering is not supported in Bluetooth mode.", Toast.LENGTH_LONG).show()
                switchGyro.isChecked = false
                return@setOnCheckedChangeListener
            }
            isGyroEnabled = isChecked
            view.isGyroEnabled = isChecked
            layoutGyroMode?.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnCalibrateSensors?.visibility = if (isChecked || isDanceModeEnabled) View.VISIBLE else View.GONE
            
            if (isChecked) {
                gravitySensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                sensorManager?.unregisterListener(this, gravitySensor)
                val state = udpSender?.state ?: bluetoothSender?.state
                if (state != null) {
                    state.lx = 0
                    state.ly = 0
                    state.rx = 0
                    state.ry = 0
                }
            }
        }
        
        switchDanceMode?.setOnCheckedChangeListener { _, isChecked ->
            isDanceModeEnabled = isChecked
            btnCalibrateSensors?.visibility = if (isChecked || isGyroEnabled) View.VISIBLE else View.GONE
            if (isChecked) {
                accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                gyroSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            } else {
                sensorManager?.unregisterListener(this, accelSensor)
                sensorManager?.unregisterListener(this, gyroSensor)
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
        
        findViewById<Button>(R.id.btnCalibrateSensors)?.setOnClickListener {
            needsGyroCalibration = true
            // Only count frames for sensors that actually exist; otherwise finishCalibration()
            // would wait forever for a counter that never decrements.
            calibrationFramesAccel = if (accelSensor != null) 30 else 0
            calibrationFramesGyro = if (gyroSensor != null) 30 else 0
            if (calibrationFramesAccel == 0 && calibrationFramesGyro == 0) {
                Toast.makeText(this, "No motion sensors available to calibrate.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sumAx = 0.0; sumAy = 0.0; sumAz = 0.0
            sumGx = 0.0; sumGy = 0.0; sumGz = 0.0
            Toast.makeText(this, "Hold phone completely still for 1 second...", Toast.LENGTH_LONG).show()
        }

        val switchRumble = findViewById<SwitchCompat>(R.id.switchRumble)
        // Fix 8: Hide Rumble toggle in Bluetooth mode (BT HID doesn't support rumble)
        if (bluetoothSender != null) {
            switchRumble?.visibility = View.GONE
        }
        switchRumble?.setOnCheckedChangeListener { _, isChecked ->
            udpSender?.isRumbleEnabled = isChecked
        }
        
        // Profiles Setup
        val spinner = findViewById<Spinner>(R.id.spinnerProfile)
        val profiles = listOf(
            GamepadProfiles.NINTENDO,
            GamepadProfiles.JOYCON_L,
            GamepadProfiles.JOYCON_R,
            GamepadProfiles.WII,
            GamepadProfiles.XBOX,
            GamepadProfiles.PSP
        )
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
                val p = profiles[position]
                view.setProfile(p)
                udpSender?.state?.joyconType = when (p.id) {
                    "joycon_l" -> 1 // Left
                    "joycon_r" -> 0 // Right
                    else -> 2       // Pro
                }.toByte()
                sharedPrefs.edit { putString("lastProfile", p.id) }
                requestedOrientation = if (p.id == "wii" || p.id == "joycon_l" || p.id == "joycon_r") {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        findViewById<Button>(R.id.btnEditLayout).setOnClickListener {
            view.enterEditMode()
            drawerLayout?.closeDrawers()
        }
        
        findViewById<android.widget.ImageButton>(R.id.btnOpenMenu)?.setOnClickListener {
            drawerLayout?.openDrawer(androidx.core.view.GravityCompat.START)
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
        discoveryJob?.cancel()
        sensorManager?.unregisterListener(this)
        udpSender?.stop()
        bluetoothSender?.destroy()
    }

    private fun updateBatteryLevel() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        udpSender?.state?.battery = ((capacity + 12) / 25).coerceIn(0, 4).toByte()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryLevel()
        if (isGyroEnabled) {
            gravitySensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (isDanceModeEnabled) {
            accelSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroSensor?.let {
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
    


    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val state = udpSender?.state
                if (state != null) {
                    var ax = event.values[0].toDouble()
                    var ay = event.values[1].toDouble()
                    var az = event.values[2].toDouble()

                    if (calibrationFramesAccel > 0) {
                        sumAx += ax
                        sumAy += ay
                        sumAz += az
                        calibrationFramesAccel--
                        
                        if (calibrationFramesAccel == 0 && calibrationFramesGyro == 0) {
                            finishCalibration()
                        }
                        return
                    }

                    // Cemuhook expects raw local hardware axes.
                    // We only convert gravity to Gs, but we do NOT apply a fixed rotation matrix
                    // because it would cause cross-talk during physical yaw.

                    state.accelX = ax.toFloat()
                    state.accelY = ay.toFloat()
                    state.accelZ = az.toFloat()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val state = udpSender?.state
                if (state != null) {
                    var gx = event.values[0].toDouble()
                    var gy = event.values[1].toDouble()
                    var gz = event.values[2].toDouble()

                    if (calibrationFramesGyro > 0) {
                        sumGx += gx
                        sumGy += gy
                        sumGz += gz
                        calibrationFramesGyro--
                        
                        if (calibrationFramesAccel == 0 && calibrationFramesGyro == 0) {
                            finishCalibration()
                        }
                        return
                    }

                    // Subtract the resting drift bias first
                    gx -= gyroBiasX
                    gy -= gyroBiasY
                    gz -= gyroBiasZ

                    // Do NOT apply a fixed rotation matrix to gyro data.
                    // The gyro axes are local. A fixed world rotation causes severe cross-talk.

                    state.gyroX = gx.toFloat()
                    state.gyroY = gy.toFloat()
                    state.gyroZ = gz.toFloat()
                }
            }
            Sensor.TYPE_GRAVITY -> {
                if (!isGyroEnabled) return
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
            if (kotlin.math.abs(smoothedTiltY) > deadzone) {
                val magnitude = (kotlin.math.abs(smoothedTiltY) - deadzone) / (6f - deadzone)
                outY = kotlin.math.sign(smoothedTiltY) * magnitude.coerceIn(0f, 1f)
                outY = outY * outY * kotlin.math.sign(outY)
            }
            
            val lx = (outY * 32767).toInt().toShort() // Left/Right steering mapped to Y tilt
            val ly = (-outX * 32767).toInt().toShort() // Up/Down pitch mapped to X tilt (inverted for standard joystick feel)
            
            // Only update if not paused!
            if (gamepadView?.isInputPaused == false) {
                val state = udpSender?.state ?: bluetoothSender?.state
                if (state != null) {
                    when (gyroMode) {
                        0 -> state.lx = lx
                        1 -> {
                            state.lx = lx
                            state.ly = ly
                        }
                        2 -> {
                            state.rx = lx
                            state.ry = ly
                        }
                    }
                }
            }
            
            // Push visual tilt to GamepadView
            gamepadView?.gyroTiltX = outY
            gamepadView?.gyroTiltY = -outX
            gamepadView?.postInvalidate()
            }
        }
    }
    private fun finishCalibration() {
        
        gyroBiasX = sumGx / 30.0
        gyroBiasY = sumGy / 30.0
        gyroBiasZ = sumGz / 30.0

        // We do not compute a base Euler angle because applying a fixed world rotation
        // to local IMU axes causes severe cross-talk when the device orientation changes.
        // The bias subtraction above is the only calibration needed.

        runOnUiThread {
            gamepadView?.triggerCalibrationFlash()
            Toast.makeText(this@MainActivity, "Sensors Calibrated & Drift Cancelled!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
