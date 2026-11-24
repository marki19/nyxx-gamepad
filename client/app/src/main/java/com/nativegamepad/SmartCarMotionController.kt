package com.nativegamepad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

class SmartCarMotionController(
    private val context: Context,
    private val sendBluetooth: (String) -> Unit,
    private val onDotUpdated: ((normX: Float, normY: Float, command: String) -> Unit)? = null
) : SensorEventListener {

    enum class Mode { NONE, AIR_MOVE }

    var maxSpeedProvider: (() -> Int)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rawAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val handler = Handler(Looper.getMainLooper())
    private var activeMode = Mode.NONE
    private var isListening = false

    // Calibration & orientation state (persistent gravity vector)
    private var hasReference = false
    private var refGx = 0f
    private var refGy = 0f
    private var curGx = 0f
    private var curGy = 0f

    // Low-pass filter to isolate gravity from hand tremors on accelerometer fallback
    private var hasFilteredGravity = false
    private val estGravity = floatArrayOf(0f, 0f, 9.81f)

    // Normalized [-1f..1f] steering coordinates
    private var normX = 0f
    private var normY = 0f
    private var isDriving = false
    private var lastCmd = "%S"

    private val streamRunnable = object : Runnable {
        override fun run() {
            if (activeMode == Mode.NONE) return
            computeAndSend()
            handler.postDelayed(this, 100L) // 10 Hz matches Arduino failsafe
        }
    }

    /** Start listening to sensors when entering the motion screen so calibration has zero cold-start delay */
    fun startListening() {
        if (isListening) return
        isListening = true
        hasFilteredGravity = false
        val sensor = gravitySensor ?: rawAccelSensor
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    /** Unregister sensor listener when exiting motion screen */
    fun stopListening() {
        stopHold(sendStop = true)
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    /** Call on button PRESS (ACTION_DOWN). Calibrates current physical angle as resting zero (super position). */
    fun startHold(mode: Mode = Mode.AIR_MOVE) {
        stopHold(sendStop = false)
        activeMode = mode
        isDriving = false
        normX = 0f
        normY = 0f

        // Ensure sensors are actively listening
        startListening()

        // Calibrate instant super position from current physical angle
        if (curGx != 0f || curGy != 0f) {
            refGx = curGx
            refGy = curGy
            hasReference = true
        } else {
            hasReference = false
        }

        sendBluetooth("%S")
        onDotUpdated?.invoke(0f, 0f, "%S")
        handler.postDelayed(streamRunnable, 100L)
    }

    /** Call on button RELEASE (ACTION_UP / ACTION_CANCEL). Immediately brakes the car. */
    fun stopHold(sendStop: Boolean = true) {
        activeMode = Mode.NONE
        isDriving = false
        hasReference = false
        lastCmd = "%S"
        handler.removeCallbacks(streamRunnable)
        normX = 0f
        normY = 0f
        if (sendStop) {
            sendBluetooth("%S")
            onDotUpdated?.invoke(0f, 0f, "%S")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        handleMotionSensor(event)
    }

    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: Surface.ROTATION_0
            } catch (e: Exception) {
                Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }
    }

    private fun mapTiltAxes(rawX: Float, rawY: Float): Pair<Float, Float> {
        return when (getDisplayRotation()) {
            Surface.ROTATION_90 -> Pair(rawY, rawX)
            Surface.ROTATION_270 -> Pair(-rawY, -rawX)
            Surface.ROTATION_180 -> Pair(rawX, rawY)
            // In portrait (ROTATION_0): invert rawX so tilting right is +X and tilting left is -X
            else -> Pair(-rawX, -rawY)
        }
    }

    private fun handleMotionSensor(event: SensorEvent) {
        var rx = 0f
        var ry = 0f
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            rx = event.values[0]
            ry = event.values[1]
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!hasFilteredGravity) {
                estGravity[0] = event.values[0]
                estGravity[1] = event.values[1]
                estGravity[2] = event.values[2]
                hasFilteredGravity = true
            } else {
                val alpha = 0.85f
                estGravity[0] = alpha * estGravity[0] + (1f - alpha) * event.values[0]
                estGravity[1] = alpha * estGravity[1] + (1f - alpha) * event.values[1]
            }
            rx = estGravity[0]
            ry = estGravity[1]
        }

        val (sx, sy) = mapTiltAxes(rx, ry)
        curGx = sx
        curGy = sy

        if (activeMode == Mode.NONE) return

        if (!hasReference) {
            refGx = curGx
            refGy = curGy
            hasReference = true
            normX = 0f
            normY = 0f
            onDotUpdated?.invoke(0f, 0f, lastCmd)
            return
        }

        // Persistent orientation: holding the phone tilted in the air relative to super position
        val dx = curGx - refGx
        val dy = curGy - refGy
        val maxTilt = 4.8f // ~28 degrees = 100% full tilt
        normX = (dx / maxTilt).coerceIn(-1f, 1f)
        normY = (dy / maxTilt).coerceIn(-1f, 1f)

        // Real-time visual feedback for the joystick knob on sensor update
        onDotUpdated?.invoke(normX, normY, lastCmd)
    }

    private fun computeAndSend() {
        val mag = hypot(normX, normY)
        // Hysteresis deadzone: requires 0.18 to begin driving, remains driving until dropping below 0.08
        val deadzone = if (isDriving) 0.08f else 0.18f

        if (mag < deadzone) {
            if (isDriving) {
                isDriving = false
                lastCmd = "%S"
                sendBluetooth("%S")
                onDotUpdated?.invoke(0f, 0f, "%S")
            }
            return
        }

        isDriving = true

        // Snap pure left/right movement to spin in place (0 deg right, 180 deg left)
        val deg = if (abs(normY) < 0.12f) {
            if (normX > 0f) 0 else 180
        } else {
            (atan2(normY, normX) * (180.0 / PI)).roundToInt()
        }

        val normalized = ((mag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        // Exponential curve: progressive feel, eliminates sudden 60-70 ramp
        val expCurve = normalized * normalized

        val userMax = maxSpeedProvider?.invoke()?.coerceIn(20, 100) ?: 100
        val minSpeed = minOf(30, userMax)
        val range = (userMax - minSpeed).coerceAtLeast(0)
        val pct = if (range == 0) userMax else (minSpeed + expCurve * range).roundToInt().coerceIn(minSpeed, userMax)

        // Arduino G_Drive accepts @<deg># with length <= 7
        val cmd = "@$deg#"
        lastCmd = cmd
        sendBluetooth(cmd)
        onDotUpdated?.invoke(normX, normY, cmd)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
