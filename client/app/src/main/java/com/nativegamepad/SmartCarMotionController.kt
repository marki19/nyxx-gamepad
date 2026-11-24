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

    enum class Mode { NONE, TILT_STEER, AIR_MOVE }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val handler = Handler(Looper.getMainLooper())
    private var activeMode = Mode.NONE

    // Tilt mode calibration & state (re-mapped to landscape screen axes)
    private var hasReference = false
    private var refGx = 0f
    private var refGy = 0f
    private var curGx = 0f
    private var curGy = 0f

    // Fallback low-pass gravity estimate (for devices without hardware gyro)
    private val estGravity = floatArrayOf(0f, 0f, 9.81f)

    // Air-Move double-integration state (velocity-damped virtual hand position)
    private var lastTimestampNs = 0L
    private var velX = 0f
    private var velY = 0f
    private var posX = 0f
    private var posY = 0f

    // Normalized [-1f..1f] coordinates for drawing a live crosshair dot in UI
    private var normX = 0f
    private var normY = 0f

    private val streamRunnable = object : Runnable {
        override fun run() {
            if (activeMode == Mode.NONE) return
            computeAndSend()
            handler.postDelayed(this, 100L) // 10 Hz matches Arduino's 500ms failsafe
        }
    }

    /** Call on button PRESS (ACTION_DOWN). Sets current hand pose as center/stop. */
    fun startHold(mode: Mode) {
        stopHold(sendStop = false)
        activeMode = mode
        hasReference = false
        lastTimestampNs = 0L
        velX = 0f; velY = 0f
        posX = 0f; posY = 0f
        normX = 0f; normY = 0f

        when (mode) {
            Mode.TILT_STEER -> {
                val sensor = gravitySensor ?: rawAccelSensor
                sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            }
            Mode.AIR_MOVE -> {
                val sensor = linearAccelSensor ?: rawAccelSensor
                sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            }
            Mode.NONE -> return
        }

        sendBluetooth("%S")
        onDotUpdated?.invoke(0f, 0f, "%S")
        handler.postDelayed(streamRunnable, 100L)
    }

    /** Call on button RELEASE (ACTION_UP / ACTION_CANCEL). Immediately brakes the car. */
    fun stopHold(sendStop: Boolean = true) {
        activeMode = Mode.NONE
        handler.removeCallbacks(streamRunnable)
        sensorManager.unregisterListener(this)
        normX = 0f
        normY = 0f
        if (sendStop) {
            sendBluetooth("%S")
            onDotUpdated?.invoke(0f, 0f, "%S")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (activeMode) {
            Mode.TILT_STEER -> handleTiltSensor(event)
            Mode.AIR_MOVE -> handleAirMoveSensor(event)
            Mode.NONE -> Unit
        }
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
            Surface.ROTATION_90 -> Pair(-rawY, rawX)
            Surface.ROTATION_270 -> Pair(rawY, -rawX)
            Surface.ROTATION_180 -> Pair(-rawX, -rawY)
            // In portrait (ROTATION_0): right roll is +X, forward pitch dips top edge down (reducing rawY),
            // so inverting rawY produces positive delta (forward driving)
            else -> Pair(rawX, -rawY)
        }
    }

    private fun mapAirMoveAxes(rawX: Float, rawY: Float): Pair<Float, Float> {
        return when (getDisplayRotation()) {
            Surface.ROTATION_90 -> Pair(-rawY, rawX)
            Surface.ROTATION_270 -> Pair(rawY, -rawX)
            Surface.ROTATION_180 -> Pair(-rawX, -rawY)
            // In portrait (ROTATION_0): moving phone to the right is +X, moving phone forward along body is +Y
            else -> Pair(rawX, rawY)
        }
    }

    private fun handleTiltSensor(event: SensorEvent) {
        var rx = 0f
        var ry = 0f
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            rx = event.values[0]
            ry = event.values[1]
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Low-pass filter isolates gravity from hand shake
            val alpha = 0.8f
            estGravity[0] = alpha * estGravity[0] + (1f - alpha) * event.values[0]
            estGravity[1] = alpha * estGravity[1] + (1f - alpha) * event.values[1]
            rx = estGravity[0]
            ry = estGravity[1]
        }

        val (sx, sy) = mapTiltAxes(rx, ry)
        curGx = sx
        curGy = sy

        if (!hasReference) {
            refGx = curGx
            refGy = curGy
            hasReference = true
        }

        // Tilt right -> +X; Tilt forward -> +Y
        val dx = curGx - refGx
        val dy = curGy - refGy
        val maxTilt = 5.5f // ~35 degrees = full speed
        normX = (dx / maxTilt).coerceIn(-1f, 1f)
        normY = (dy / maxTilt).coerceIn(-1f, 1f)
    }

    private fun handleAirMoveSensor(event: SensorEvent) {
        if (lastTimestampNs == 0L) {
            lastTimestampNs = event.timestamp
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                estGravity[0] = event.values[0]
                estGravity[1] = event.values[1]
                estGravity[2] = event.values[2]
            }
            return
        }

        val dt = ((event.timestamp - lastTimestampNs) * 1e-9f).coerceIn(0.005f, 0.1f)
        lastTimestampNs = event.timestamp

        var rx = 0f
        var ry = 0f
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            rx = event.values[0]
            ry = event.values[1]
        } else {
            // High-pass filter extracts linear motion when TYPE_LINEAR_ACCELERATION is unavailable
            val alpha = 0.85f
            estGravity[0] = alpha * estGravity[0] + (1f - alpha) * event.values[0]
            estGravity[1] = alpha * estGravity[1] + (1f - alpha) * event.values[1]
            rx = event.values[0] - estGravity[0]
            ry = event.values[1] - estGravity[1]
        }

        val (rawAx, rawAy) = mapAirMoveAxes(rx, ry)
        var ax = rawAx
        var ay = rawAy

        // Noise gate ignores tiny wrist tremors
        if (abs(ax) < 0.45f) ax = 0f
        if (abs(ay) < 0.45f) ay = 0f

        // Velocity-damped double integration: raising hand shifts posY up and holds it there
        velX = (velX + ax * dt) * 0.84f
        velY = (velY + ay * dt) * 0.84f

        posX = (posX + velX * dt).coerceIn(-0.35f, 0.35f)
        posY = (posY + velY * dt).coerceIn(-0.35f, 0.35f)

        // Gentle self-centering bleed prevents long-term sensor drift
        posX *= 0.994f
        posY *= 0.994f

        val maxReach = 0.25f // ~25 cm hand sweep = 100% speed
        normX = (posX / maxReach).coerceIn(-1f, 1f)
        normY = (posY / maxReach).coerceIn(-1f, 1f)
    }

    private fun computeAndSend() {
        val deadzone = if (activeMode == Mode.TILT_STEER) 0.20f else 0.25f
        val mag = hypot(normX, normY)

        if (mag < deadzone) {
            sendBluetooth("%S")
            onDotUpdated?.invoke(normX, normY, "%S")
            return
        }

        // Snap pure left/right movement to spin in place (0 deg right, 180 deg left)
        val deg = if (abs(normY) < deadzone * 0.8f) {
            if (normX > 0f) 0 else 180
        } else {
            (atan2(normY, normX) * (180.0 / PI)).roundToInt()
        }

        val scaled = ((mag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val pct = (25 + scaled * 75).roundToInt().coerceIn(25, 100)

        val cmd = "@$deg,$pct#"
        sendBluetooth(cmd)
        onDotUpdated?.invoke(normX, normY, cmd)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
