package com.nativegamepad

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.OnBackPressedCallback
import android.os.Bundle
import android.os.BatteryManager

import android.view.View
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
        ThemeConfig.load(this)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemBars()
        
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isInEditMode = gamepadView?.isEditMode == true
                if (isInEditMode) {
                    // Let saveEditMode() -> onEditModeExited handle all dismissal animations
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
        
        val mode = intent?.getStringExtra("CONNECTION_MODE") ?: "WIFI"
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
    private fun applyTheme() {
        val rootLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rootLayout)
        if (rootLayout == null) {
            // Set id in layout or just use window decor view
            window.decorView.setBackgroundColor(ThemeConfig.backgroundColor)
        } else {
            rootLayout.setBackgroundColor(ThemeConfig.backgroundColor)
        }

        val tvNyxx = findViewById<TextView>(R.id.tvNyxx)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
        val btnConnect = findViewById<Button>(R.id.connectButton)
        val btnBackToHome = findViewById<android.widget.ImageButton>(R.id.btnBackToHome)

        val pColor = ThemeConfig.primaryColor
        
        tvNyxx?.setShadowLayer(12f, 0f, 4f, pColor)
        tvSubtitle?.setTextColor(pColor)
        btnConnect?.setTextColor(pColor)
        btnBackToHome?.setColorFilter(pColor)
    }

    private fun setupHomePage() {
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.ipInput)
        val button = findViewById<Button>(R.id.connectButton)
        val tvDiscoveryStatus = findViewById<TextView>(R.id.tvDiscoveryStatus)
        val llDiscoveredServers = findViewById<LinearLayout>(R.id.llDiscoveredServers)
        val btnConnectManually = findViewById<TextView>(R.id.btnConnectManually)
        val layoutManualInput = findViewById<LinearLayout>(R.id.layoutManualInput)
        
        applyTheme()
        findViewById<TextView>(R.id.tvAppVersion)?.text = "v" + BuildConfig.VERSION_NAME

        val prefs = getSharedPreferences("GamepadPrefs", Context.MODE_PRIVATE)

        btnConnectManually?.setOnClickListener {
            layoutManualInput?.visibility = View.VISIBLE
            btnConnectManually?.visibility = View.GONE
        }

        val autoIp = intent?.getStringExtra("AUTO_CONNECT_IP")
        val mode = intent?.getStringExtra("CONNECTION_MODE") ?: "WIFI"
        
        if (mode == "USB") {
            findViewById<TextView>(R.id.tvSubtitle)?.text = "USB TETHERING CONNECTION"
            findViewById<TextView>(R.id.tvDiscoveryStatus)?.text = "Enter USB Tethered IP:"
            layoutManualInput?.visibility = View.VISIBLE
            btnConnectManually?.visibility = View.GONE
            input?.setText(prefs.getString("last_usb_ip", ""))
        } else {
            if (!autoIp.isNullOrBlank()) {
                startGamepadDirectly(autoIp, fromAutoIntent = true)
            } else {
                input?.setText(prefs.getString("last_ip", ""))
                startDiscoveryScanner()
            }
        }
        
        button?.setOnClickListener {
            val ipString = input?.text?.toString()?.trim() ?: ""
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

                if (mode == "USB") {
                    prefs.edit { putString("last_usb_ip", ipString) }
                } else {
                    prefs.edit { putString("last_ip", ipString) }
                }

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
    }

    private fun startDiscoveryScanner() {
        val tvDiscoveryStatus = findViewById<TextView>(R.id.tvDiscoveryStatus) ?: return
        val llDiscoveredServers = findViewById<LinearLayout>(R.id.llDiscoveredServers) ?: return
        val discovery = ServerDiscovery()

        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            while (isActive) {
                val servers = discovery.discoverServers()
                withContext(Dispatchers.Main) {
                    if (servers.isNotEmpty()) {
                        tvDiscoveryStatus.text = "Available Servers:"
                        llDiscoveredServers.removeAllViews()

                        for (server in servers) {
                            val btn = Button(this@MainActivity).apply {
                                text = "🖥️ ${server.hostname} (${server.ip})"
                                isAllCaps = false
                                textSize = 14f
                                setBackgroundResource(R.drawable.btn_premium_card)
                                setTextColor(android.graphics.Color.WHITE)
                                setPadding(24, 24, 24, 24)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 0, 0, 12)
                                }
                                
                                setOnClickListener {
                                    startGamepadDirectly("${server.ip}:${server.port}", fromAutoIntent = false)
                                }
                            }
                            llDiscoveredServers.addView(btn)
                        }
                    } else {
                        tvDiscoveryStatus.text = "Searching for available servers..."
                        llDiscoveredServers.removeAllViews()
                    }
                }
                kotlinx.coroutines.delay(2500)
            }
        }
    }

    private fun startGamepadDirectly(ipWithPort: String, fromAutoIntent: Boolean = false) {
        var ip = ipWithPort
        var port = 5000

        if (ipWithPort.contains(":")) {
            val parts = ipWithPort.split(":")
            ip = parts[0]
            port = parts[1].toIntOrNull() ?: 5000
        }

        // Cancel discovery
        discoveryJob?.cancel()

        // Connect immediately
        thread {
            val playerIndex = UdpSender.pingServer(ip, port)
            runOnUiThread {
                if (playerIndex > 0) {
                    val prefs = getSharedPreferences("GamepadPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_ip", ipWithPort).apply()
                    startGamepad(ip, port, playerIndex)
                } else {
                    showConnectionError(playerIndex, ip, port)
                    if (fromAutoIntent) {
                        val intent = Intent(this@MainActivity, HomeActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        findViewById<LinearLayout>(R.id.layoutManualInput)?.visibility = View.VISIBLE
                        findViewById<TextView>(R.id.btnConnectManually)?.visibility = View.GONE
                        startDiscoveryScanner()
                    }
                }
            }
        }
    }

    // Helper function for error handling
    private fun showConnectionError(playerIndex: Int, ip: String, port: Int) {
        val title: String
        val message: String
        if (playerIndex == 0) {
            title = "Server Full"
            message = "The server at $ip:$port already has 8 players connected.\n\nPlease wait for a player to disconnect and try again."
        } else {
            title = "Connection Failed"
            message = "Could not connect to $ip:$port.\nMake sure the server is running and your PC firewall allows UDP port $port."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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

        // Set orientation BEFORE setContentView to prevent layout flash.
        // The spinner will fire onItemSelected after the view is inflated, but by that
        // point the activity is already in the right orientation so no recreation occurs.
        val lastProfileId = getSharedPreferences("GamepadPrefs", Context.MODE_PRIVATE)
            .getString("lastProfile", "nintendo") ?: "nintendo"
        requestedOrientation = if (lastProfileId == "wii" || lastProfileId == "joycon_l" || lastProfileId == "joycon_r") {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

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
        bluetoothSender?.onDisconnected = {
            runOnUiThread {
                bluetoothSender?.stop()
                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
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
            val floatingBox = findViewById<View>(R.id.editModeFloatingBox)
            val overlay2 = findViewById<View>(R.id.editModeOverlay)

            floatingBox?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
                floatingBox.visibility = View.GONE
                floatingBox.alpha = 1f
            }?.start()
            
            // Fade out overlay
            overlay2?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                overlay2.visibility = View.GONE
                overlay2.alpha = 1f
            }?.start()
        }
        
        setupSidebar()
        setupEditModeFloatingBox()
        applyGamepadTheme()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupEditModeFloatingBox() {
        val view = gamepadView ?: return
        val floatingBox = findViewById<LinearLayout>(R.id.editModeFloatingBox) ?: return
        val header = floatingBox.findViewById<View>(R.id.editModeHeader)

        // Dragging Logic
        var dX = 0f
        var dY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = floatingBox.x - event.rawX
                    dY = floatingBox.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatingBox.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }

        // Preview / Eye toggle
        val btnEye = floatingBox.findViewById<android.widget.FrameLayout>(R.id.btnPreviewToggle)
        val tvEye = floatingBox.findViewById<ImageView>(R.id.tvEyeIcon)
        val eyeRing = floatingBox.findViewById<View>(R.id.eyeActiveRing)
        val propertiesContent = floatingBox.findViewById<View>(R.id.layoutPropertiesContent)

        fun updateEyeState(isPreview: Boolean) {
            if (isPreview) {
                // Bright, fully opaque — clearly ON
                tvEye?.alpha = 1f
                eyeRing?.animate()?.alpha(1f)?.setDuration(150)?.start()
                eyeRing?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ThemeConfig.primaryColor
                )
                propertiesContent?.visibility = View.GONE  // hide properties in preview
            } else {
                // Dim — clearly OFF
                tvEye?.alpha = 0.35f
                eyeRing?.animate()?.alpha(0f)?.setDuration(150)?.start()
                // Re-show properties if a group was selected
                val tvTitle = floatingBox.findViewById<TextView>(R.id.tvSheetTitle)
                if (tvTitle?.text != "PROPERTIES" && tvTitle?.text?.startsWith("✦") == true) {
                    propertiesContent?.visibility = View.VISIBLE
                }
            }
        }

        btnEye?.setOnClickListener {
            view.togglePreviewMode()
            updateEyeState(view.isPreviewMode)
        }
        updateEyeState(false)

        // DONE button — save and exit (onEditModeExited drives all animations)
        floatingBox.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnDoneEdit)?.setOnClickListener {
            view.saveEditMode()
        }
        // CANCEL button — discard and exit
        floatingBox.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnCancelAllEdit)?.setOnClickListener {
            view.cancelEditMode()
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun showButtonPropertiesDialog(groupName: String) {
        val view = gamepadView ?: return
        val floatingBox = findViewById<LinearLayout>(R.id.editModeFloatingBox) ?: return
        val propertiesContent = floatingBox.findViewById<View>(R.id.layoutPropertiesContent)
        
        // Ensure the content section is visible now that we selected something
        if (propertiesContent?.visibility != View.VISIBLE && !view.isPreviewMode) {
            propertiesContent?.visibility = View.VISIBLE
        }

        val tvTitle = floatingBox.findViewById<TextView>(R.id.tvSheetTitle)
        val tvSize = floatingBox.findViewById<TextView>(R.id.tvSizeLabel)
        val btnSizeMinus = floatingBox.findViewById<Button>(R.id.btnSizeMinus)
        val btnSizePlus = floatingBox.findViewById<Button>(R.id.btnSizePlus)
        val tvSpacing = floatingBox.findViewById<TextView>(R.id.tvSpacingLabel)
        val btnSpacingMinus = floatingBox.findViewById<Button>(R.id.btnSpacingMinus)
        val btnSpacingPlus = floatingBox.findViewById<Button>(R.id.btnSpacingPlus)
        val rowSpacing = floatingBox.findViewById<LinearLayout>(R.id.rowSpacing)
        val tvOpacity = floatingBox.findViewById<TextView>(R.id.tvOpacityLabel)
        val btnOpacityMinus = floatingBox.findViewById<Button>(R.id.btnOpacityMinus)
        val btnOpacityPlus = floatingBox.findViewById<Button>(R.id.btnOpacityPlus)
        val switchVisible = floatingBox.findViewById<SwitchCompat>(R.id.switchVisible)
        val switchTurbo = floatingBox.findViewById<SwitchCompat>(R.id.switchTurbo)
        val switchAnalog = floatingBox.findViewById<SwitchCompat>(R.id.switchAnalog)

        tvTitle?.text = "✦ ${groupName.uppercase()}"
        val config = view.buttonConfigs[groupName] ?: ButtonConfig()

        fun updateUI() {
            tvSize?.text = "${"%.1f".format(config.scale)}×"
            tvSpacing?.text = "${"%.1f".format(config.spacing)}×"
            tvOpacity?.text = "${(config.opacity * 100).toInt()}%"
            rowSpacing?.visibility = if (groupName == "ABXY" || groupName == "DPad") View.VISIBLE else View.INVISIBLE
            switchVisible?.isChecked = config.visible
            switchTurbo?.isChecked = config.turbo
            switchAnalog?.isChecked = config.analogTrigger
            switchAnalog?.visibility = if (groupName == "L2" || groupName == "R2") View.VISIBLE else View.GONE
        }
        updateUI()

        btnSizeMinus?.setOnClickListener {
            config.scale = (config.scale - 0.1f).coerceAtLeast(0.5f)
            view.buttonConfigs[groupName] = config; view.applyScales(); view.invalidate(); view.saveConfigState(); updateUI()
        }
        btnSizePlus?.setOnClickListener {
            config.scale = (config.scale + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config; view.applyScales(); view.invalidate(); view.saveConfigState(); updateUI()
        }
        btnSpacingMinus?.setOnClickListener {
            config.spacing = (config.spacing - 0.1f).coerceAtLeast(0.5f)
            view.buttonConfigs[groupName] = config; view.applyScales(); view.invalidate(); view.saveConfigState(); updateUI()
        }
        btnSpacingPlus?.setOnClickListener {
            config.spacing = (config.spacing + 0.1f).coerceAtMost(2.0f)
            view.buttonConfigs[groupName] = config; view.applyScales(); view.invalidate(); view.saveConfigState(); updateUI()
        }
        btnOpacityMinus?.setOnClickListener {
            config.opacity = (config.opacity - 0.1f).coerceAtLeast(0.1f)
            view.buttonConfigs[groupName] = config; view.saveConfigState(); view.invalidate(); updateUI()
        }
        btnOpacityPlus?.setOnClickListener {
            config.opacity = (config.opacity + 0.1f).coerceAtMost(1.0f)
            view.buttonConfigs[groupName] = config; view.saveConfigState(); view.invalidate(); updateUI()
        }
        switchVisible?.setOnCheckedChangeListener { _, isChecked ->
            config.visible = isChecked; view.buttonConfigs[groupName] = config; view.saveConfigState(); view.invalidate()
        }
        switchTurbo?.setOnCheckedChangeListener { _, isChecked ->
            config.turbo = isChecked; view.buttonConfigs[groupName] = config; view.saveConfigState()
        }
        switchAnalog?.setOnCheckedChangeListener { _, isChecked ->
            config.analogTrigger = isChecked; view.buttonConfigs[groupName] = config; view.saveConfigState()
        }
    }

    private fun setupSidebar() {
        val view = gamepadView ?: return
        
        val layoutGyroMode = findViewById<LinearLayout>(R.id.layoutGyroMode)
        val spinnerGyroMode = findViewById<Spinner>(R.id.spinnerGyroMode)
        val switchGyro = findViewById<SwitchCompat>(R.id.switchGyro)
        val switchDanceMode = findViewById<SwitchCompat>(R.id.switchDanceMode)
        
        // Sync initial state
        isGyroEnabled = switchGyro?.isChecked == true
        view.isGyroEnabled = isGyroEnabled

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
            view.invalidate()
            
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
        
        // Sync initial state
        isDanceModeEnabled = switchDanceMode?.isChecked == true
        view.isDanceModeEnabled = isDanceModeEnabled

        switchDanceMode?.setOnCheckedChangeListener { _, isChecked ->
            isDanceModeEnabled = isChecked
            view.isDanceModeEnabled = isChecked
            view.invalidate()
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
        
        view.onCalibrateRequested = {
            needsGyroCalibration = true
            // Only count frames for sensors that actually exist; otherwise finishCalibration()
            // would wait forever for a counter that never decrements.
            calibrationFramesAccel = if (accelSensor != null) 30 else 0
            calibrationFramesGyro = if (gyroSensor != null) 30 else 0
            if (calibrationFramesAccel == 0 && calibrationFramesGyro == 0) {
                Toast.makeText(this, "No motion sensors available to calibrate.", Toast.LENGTH_SHORT).show()
            } else {
                sumAx = 0.0; sumAy = 0.0; sumAz = 0.0
                sumGx = 0.0; sumGy = 0.0; sumGz = 0.0
                Toast.makeText(this, "Hold phone completely still for 1 second...", Toast.LENGTH_LONG).show()
            }
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
        val tvSelectedProfile = findViewById<TextView>(R.id.tvSelectedProfile)
        val profiles = listOf(
            GamepadProfiles.NINTENDO,
            GamepadProfiles.JOYCON_L,
            GamepadProfiles.JOYCON_R,
            GamepadProfiles.WII,
            GamepadProfiles.XBOX,
            GamepadProfiles.PSP
        )
        
        val sharedPrefs = getSharedPreferences("GamepadPrefs", android.content.Context.MODE_PRIVATE)
        val lastProfileId = sharedPrefs.getString("lastProfile", "nintendo")
        var currentProfile = profiles.find { it.id == lastProfileId } ?: GamepadProfiles.NINTENDO
        
        val listPopupWindow = androidx.appcompat.widget.ListPopupWindow(this)
        listPopupWindow.anchorView = tvSelectedProfile
        listPopupWindow.verticalOffset = 16 // A little below the display box
        listPopupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#15171E")))
        
        fun applyProfile(p: GamepadProfile) {
            currentProfile = p
            tvSelectedProfile?.text = p.displayName
            view.setProfile(p)
            udpSender?.state?.joyconType = when (p.id) {
                "joycon_l" -> 1.toByte() // Left
                "joycon_r" -> 0.toByte() // Right
                else -> 2.toByte()       // Pro
            }
            sharedPrefs.edit { putString("lastProfile", p.id) }
            requestedOrientation = if (p.id == "wii" || p.id == "joycon_l" || p.id == "joycon_r") {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            
            val switchGyro = findViewById<SwitchCompat>(R.id.switchGyro)
            val layoutGyroMode = findViewById<LinearLayout>(R.id.layoutGyroMode)
            if (!p.hasLeftStick && !p.hasSecondStick) {
                switchGyro?.visibility = View.GONE
                switchGyro?.isChecked = false
                layoutGyroMode?.visibility = View.GONE
            } else {
                switchGyro?.visibility = View.VISIBLE
                layoutGyroMode?.visibility = if (switchGyro?.isChecked == true) View.VISIBLE else View.GONE
            }
        }
        
        // Initial Selection
        applyProfile(currentProfile)
        
        tvSelectedProfile?.setOnClickListener {
            // Filter out the currently active profile
            val availableProfiles = profiles.filter { it.id != currentProfile.id }
            val adapter = ArrayAdapter(this, R.layout.spinner_item, availableProfiles.map { it.displayName })
            listPopupWindow.setAdapter(adapter)
            listPopupWindow.setOnItemClickListener { _, _, position, _ ->
                applyProfile(availableProfiles[position])
                listPopupWindow.dismiss()
            }
            listPopupWindow.show()
        }

        findViewById<Button>(R.id.btnEditLayout).setOnClickListener {
            view.enterEditMode()
            drawerLayout?.closeDrawers()
            
            val overlay = findViewById<View>(R.id.editModeOverlay)
            val floatingBox = findViewById<View>(R.id.editModeFloatingBox)
            
            // Reset floating box position to center
            floatingBox?.x = (resources.displayMetrics.widthPixels - resources.displayMetrics.density * 340) / 2f
            floatingBox?.y = (resources.displayMetrics.heightPixels - resources.displayMetrics.density * 200) / 2f
            
            // Ensure properties content is hidden until an element is selected
            floatingBox?.findViewById<View>(R.id.layoutPropertiesContent)?.visibility = View.GONE
            floatingBox?.findViewById<TextView>(R.id.tvSheetTitle)?.text = "Select a button"
            
            if (overlay?.visibility != View.VISIBLE) {
                overlay?.alpha = 0f
                overlay?.visibility = View.VISIBLE
                overlay?.animate()?.alpha(1f)?.setDuration(220)?.start()
            }
            if (floatingBox?.visibility != View.VISIBLE) {
                floatingBox?.alpha = 0f
                floatingBox?.visibility = View.VISIBLE
                floatingBox?.animate()?.alpha(1f)?.setDuration(220)?.start()
            }
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
    


    private fun validateSensorData(event: SensorEvent): Boolean {
        return when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1] 
                val z = event.values[2]
                x in -100f..100f && y in -100f..100f && z in -100f..100f
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                x in -2000f..2000f && y in -2000f..2000f && z in -2000f..2000f
            }
            Sensor.TYPE_GRAVITY -> {
                val x = event.values[0]
                val y = event.values[1]
                x in -50f..50f && y in -50f..50f
            }
            else -> true
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (!validateSensorData(event)) return
        
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

                if (!isGyroEnabled) return

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

    private fun applyMainTheme() {
        val pColor = ThemeConfig.primaryColor
        findViewById<TextView>(R.id.tvNyxx)?.setShadowLayer(12f, 0f, 4f, pColor)
        findViewById<TextView>(R.id.tvSubtitle)?.setTextColor(pColor)
        findViewById<Button>(R.id.connectButton)?.setTextColor(pColor)
        findViewById<android.widget.ImageButton>(R.id.btnBackToHome)?.setColorFilter(pColor)
    }

    private fun applyGamepadTheme() {
        val pColor = ThemeConfig.primaryColor
        val bgColor = ThemeConfig.backgroundColor
        // Accent tints
        findViewById<android.widget.ImageButton>(R.id.btnOpenMenu)?.setColorFilter(pColor)
        findViewById<TextView>(R.id.tvSheetTitle)?.setTextColor(pColor)
        findViewById<TextView>(R.id.tvMenuTitle)?.setTextColor(pColor)
        
        // Sidebar headers & Quick Calibrate button
        findViewById<TextView>(R.id.tvSectionProfile)?.setTextColor(pColor)
        findViewById<TextView>(R.id.tvSectionSensors)?.setTextColor(pColor)
        findViewById<TextView>(R.id.tvSectionSystem)?.setTextColor(pColor)

        // Tint the DONE button in the new top bar
        val floatingBox = findViewById<LinearLayout>(R.id.editModeFloatingBox)
        floatingBox?.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnDoneEdit)
            ?.backgroundTintList = android.content.res.ColorStateList.valueOf(pColor)

        // Background propagation
        drawerLayout?.setBackgroundColor(bgColor)
    }
}
