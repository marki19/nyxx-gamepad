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

    // Calibration & orientation state (persistent gravity vector)
    private var hasReference = false
    private var refGx = 0f
    private var refGy = 0f
    private var curGx = 0f
    private var curGy = 0f

    // Low-pass filter to eliminate hand tremors
    private val estGravity = floatArrayOf(0f, 0f, 9.81f)

    // Normalized [-1f..1f] steering coordinates
    private var normX = 0f
    private var normY = 0f

    private val streamRunnable = object : Runnable {
        override fun run() {
            if (activeMode == Mode.NONE) return
            computeAndSend()
            handler.postDelayed(this, 100L) // 10 Hz matches Arduino failsafe
        }
    }

    /** Call on button PRESS (ACTION_DOWN). Calibrates current physical angle as resting zero. */
    fun startHold(mode: Mode = Mode.AIR_MOVE) {
        stopHold(sendStop = false)
        activeMode = mode
        hasReference = false
        normX = 0f
        normY = 0f

        val sensor = gravitySensor ?: rawAccelSensor
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

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
        if (activeMode == Mode.NONE) return
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
            Surface.ROTATION_90 -> Pair(-rawY, rawX)
            Surface.ROTATION_270 -> Pair(rawY, -rawX)
            Surface.ROTATION_180 -> Pair(-rawX, -rawY)
            // In portrait (ROTATION_0): right roll is +X, forward pitch dips top edge down (reducing rawY),
            // inverting rawY produces positive delta (forward driving)
            else -> Pair(rawX, -rawY)
        }
    }

    private fun handleMotionSensor(event: SensorEvent) {
        var rx = 0f
        var ry = 0f
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            rx = event.values[0]
            ry = event.values[1]
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Low-pass filter isolates persistent gravity from hand tremors
            val alpha = 0.82f
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

        // Persistent orientation: holding the phone tilted in the air NEVER depletes
        val dx = curGx - refGx
        val dy = curGy - refGy
        val maxTilt = 4.8f // ~28 degrees = 100% full tilt
        normX = (dx / maxTilt).coerceIn(-1f, 1f)
        normY = (dy / maxTilt).coerceIn(-1f, 1f)
    }

    private fun computeAndSend() {
        val deadzone = 0.15f
        val mag = hypot(normX, normY)

        if (mag < deadzone) {
            sendBluetooth("%S")
            onDotUpdated?.invoke(normX, normY, "%S")
            return
        }

        // Snap pure left/right movement to spin in place (0 deg right, 180 deg left)
        val deg = if (abs(normY) < deadzone * 0.75f) {
            if (normX > 0f) 0 else 180
        } else {
            (atan2(normY, normX) * (180.0 / PI)).roundToInt()
        }

        val normalized = ((mag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        // Exponential curve: progressive feel, eliminates sudden 60-70 ramp
        val expCurve = normalized * normalized

        val userMax = maxSpeedProvider?.invoke()?.coerceIn(25, 100) ?: 100
        val minSpeed = 30 // minimum PWM to clear motor stall (MIN_PWM = 60)
        val pct = (minSpeed + expCurve * (userMax - minSpeed)).roundToInt().coerceIn(minSpeed, userMax)

        val cmd = "@$deg,$pct#"
        sendBluetooth(cmd)
        onDotUpdated?.invoke(normX, normY, cmd)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
