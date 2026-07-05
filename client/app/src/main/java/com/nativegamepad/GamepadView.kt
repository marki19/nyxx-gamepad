package com.nativegamepad

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

object GamepadColors {
    val neutralBase = Color.parseColor("#1C1F24")
    val neutralTop = Color.parseColor("#3D4249")
    val neutralStroke = Color.parseColor("#5B6270")
    val neutralText = Color.parseColor("#EDEEF0")

    val yBase = Color.parseColor("#1F7A4D"); val yTop = Color.parseColor("#3DDC84"); val yText = Color.parseColor("#0B3320")
    val xBase = Color.parseColor("#1B6FA0"); val xTop = Color.parseColor("#4FC3F7"); val xText = Color.parseColor("#0A2E42")
    val aBase = Color.parseColor("#A33131"); val aTop = Color.parseColor("#FF5C5C"); val aText = Color.parseColor("#4A1414")
    val bBase = Color.parseColor("#A67B14"); val bTop = Color.parseColor("#FFD54F"); val bText = Color.parseColor("#4A3607")
}

data class ButtonSpec(
    val name: String,
    val bit: Int,
    val group: String,
    val isTrigger: Boolean = false,
    val isL2: Boolean = false,
    val isDpad: Boolean = false,
    val isMenu: Boolean = false,
    val isOverlayUI: Boolean = false
)

data class GamepadProfile(
    val id: String,
    val displayName: String,
    val hasSecondStick: Boolean,
    val hasTriggers: Boolean,
    val buttons: List<ButtonSpec>
)

object GamepadProfiles {
    val NINTENDO = GamepadProfile(
        id = "nintendo",
        displayName = "Nintendo",
        hasSecondStick = true,
        hasTriggers = true,
        buttons = listOf(
            ButtonSpec("A", 0x2000, "ABXY"),
            ButtonSpec("B", 0x1000, "ABXY"),
            ButtonSpec("X", 0x8000, "ABXY"),
            ButtonSpec("Y", 0x4000, "ABXY"),
            ButtonSpec("U", 0x0001, "DPad", isDpad = true),
            ButtonSpec("D", 0x0002, "DPad", isDpad = true),
            ButtonSpec("L", 0x0004, "DPad", isDpad = true),
            ButtonSpec("R", 0x0008, "DPad", isDpad = true),
            ButtonSpec("L", 0x0100, "L1"),
            ButtonSpec("R", 0x0200, "R1"),
            ButtonSpec("ZL", 0, "L2", isTrigger = true, isL2 = true),
            ButtonSpec("ZR", 0, "R2", isTrigger = true, isL2 = false),
            ButtonSpec("-", 0x0020, "Sel"),
            ButtonSpec("+", 0x0010, "St"),
            ButtonSpec("Home", 0x0400, "Home")
        )
    )
    val XBOX = GamepadProfile(
        id = "xbox",
        displayName = "Xbox",
        hasSecondStick = true,
        hasTriggers = true,
        buttons = listOf(
            ButtonSpec("A", 0x1000, "ABXY"),
            ButtonSpec("B", 0x2000, "ABXY"),
            ButtonSpec("X", 0x4000, "ABXY"),
            ButtonSpec("Y", 0x8000, "ABXY"),
            ButtonSpec("U", 0x0001, "DPad", isDpad = true),
            ButtonSpec("D", 0x0002, "DPad", isDpad = true),
            ButtonSpec("L", 0x0004, "DPad", isDpad = true),
            ButtonSpec("R", 0x0008, "DPad", isDpad = true),
            ButtonSpec("L1", 0x0100, "L1"),
            ButtonSpec("R1", 0x0200, "R1"),
            ButtonSpec("L2", 0, "L2", isTrigger = true, isL2 = true),
            ButtonSpec("R2", 0, "R2", isTrigger = true, isL2 = false),
            ButtonSpec("Sel", 0x0020, "Sel"),
            ButtonSpec("St", 0x0010, "St"),
            ButtonSpec("Home", 0x0400, "Home")
        )
    )
    val PSP = GamepadProfile(
        id = "psp",
        displayName = "PSP",
        hasSecondStick = false,
        hasTriggers = false,
        buttons = listOf(
            ButtonSpec("Cross", 0x1000, "ABXY"),
            ButtonSpec("Circle", 0x2000, "ABXY"),
            ButtonSpec("Square", 0x4000, "ABXY"),
            ButtonSpec("Triangle", 0x8000, "ABXY"),
            ButtonSpec("U", 0x0001, "DPad", isDpad = true),
            ButtonSpec("D", 0x0002, "DPad", isDpad = true),
            ButtonSpec("L", 0x0004, "DPad", isDpad = true),
            ButtonSpec("R", 0x0008, "DPad", isDpad = true),
            ButtonSpec("L1", 0x0100, "L1"),
            ButtonSpec("R1", 0x0200, "R1"),
            ButtonSpec("Sel", 0x0020, "Sel"),
            ButtonSpec("St", 0x0010, "St"),
            ButtonSpec("Home", 0x0400, "Home")
        )
    )
}

data class ButtonConfig(
    var scale: Float = 1.0f,
    var spacing: Float = 1.0f,
    var opacity: Float = 1.0f,
    var visible: Boolean = true,
    var turbo: Boolean = false,
    var analogTrigger: Boolean = false,
    var colorOverride: Int? = null
)

class GamepadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var udpSender: UdpSender? = null
    var bluetoothSender: BluetoothHidSender? = null
    var onMenuRequested: (() -> Unit)? = null
    var onButtonEditRequested: ((String) -> Unit)? = null
    var onEditModeExited: (() -> Unit)? = null

    var isGyroEnabled: Boolean = false
    var gyroTargetMode: Int = 0 // 0=L(X), 1=L(X+Y), 2=R(X+Y)
    
    var playerIndex = 1
        set(value) {
            field = value
            invalidate()
        }

    private fun getPlayerColor(): Int {
        return when (playerIndex) {
            1 -> GamepadColors.xTop // Blue
            2 -> GamepadColors.aTop // Red
            3 -> GamepadColors.yTop // Green
            4 -> GamepadColors.bTop // Yellow
            else -> GamepadColors.xTop
        }
    }

    var gyroTiltX = 0f
    var gyroTiltY = 0f
    private var calibrationFlashAlpha = 0f

    fun triggerCalibrationFlash() {
        calibrationFlashAlpha = 1.0f
        invalidate()
    }

    var currentProfile: GamepadProfile = GamepadProfiles.NINTENDO
    val buttonConfigs = mutableMapOf<String, ButtonConfig>()

    var isInputPaused: Boolean = false

    private val paint = Paint().apply { 
        isAntiAlias = true 
        typeface = Typeface.MONOSPACE
    }
    
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1F2833")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val prefs = context.getSharedPreferences("GamepadPrefs", Context.MODE_PRIVATE)
    var showTelemetry = prefs.getBoolean("showTelemetry", false)

    private var isEditMode = false
    private var draggingGroup: String? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragInitialOffsetX = 0f
    private var dragInitialOffsetY = 0f
    private var didDrag = false
    private var dragPointerId = -1

    private val groupOffsets = mutableMapOf<String, PointF>()
    private val originalGroupOffsets = mutableMapOf<String, PointF>()
    private val groupNames = listOf("LeftJoy", "RightJoy", "DPad", "ABXY", "L1", "L2", "R1", "R2", "Sel", "St", "Home")

    class Joystick(var group: String, var cx: Float = 0f, var cy: Float = 0f, var baseRadius: Float = 0f) {
        var radius = 0f
        var knobX = 0f
        var knobY = 0f
        var pointerId = -1
        fun contains(x: Float, y: Float) = hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius * 1.3f
    }

    private val leftJoy = Joystick("LeftJoy")
    private val rightJoy = Joystick("RightJoy")

    class ButtonDef(val spec: ButtonSpec) {
        val name get() = spec.name
        val bit get() = spec.bit
        val group get() = spec.group
        val isTrigger get() = spec.isTrigger
        val isL2 get() = spec.isL2
        val isDpad get() = spec.isDpad
        val isOverlayUI get() = spec.isOverlayUI
        val isShoulder get() = group == "L1" || group == "R1" || group == "L2" || group == "R2"
        
        var cx: Float = 0f
        var cy: Float = 0f
        var radius: Float = 0f
        var isPressed: Boolean = false
        var pointerId: Int = -1
        var analogValue: Byte = 0
        fun contains(x: Float, y: Float): Boolean {
            return if (isShoulder) {
                val w2 = radius * 1.5f
                val h2 = radius * 0.75f
                x >= cx - w2 - 20f && x <= cx + w2 + 20f && y >= cy - h2 - 20f && y <= cy + h2 + 20f
            } else {
                val hitPadding = if (group == "Home" || group == "Sel" || group == "St") 1.1f else 1.5f
                hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius * hitPadding
            }
        }
    }

    private val buttons = mutableListOf<ButtonDef>()
    private val activeTouches = mutableMapOf<Int, ButtonDef>()

    private var turboState = false
    private val turboRunnable = object : Runnable {
        override fun run() {
            turboState = !turboState
            var needsUpdate = false
            for (btn in buttons) {
                if (btn.isPressed) {
                    val config = buttonConfigs[btn.group] ?: ButtonConfig()
                    if (config.turbo) {
                        needsUpdate = true
                    }
                }
            }
            if (needsUpdate) {
                updateButtonState()
            }
            postDelayed(this, 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(turboRunnable, 50)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(turboRunnable)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    
    fun setProfile(profile: GamepadProfile) {
        currentProfile = profile
        buttons.clear()
        activeTouches.clear()
        
        groupOffsets.clear()
        buttonConfigs.clear()
        val pid = profile.id
        for (g in groupNames) {
            val ox = prefs.getFloat("offX_${pid}_$g", 0f)
            val oy = prefs.getFloat("offY_${pid}_$g", 0f)
            groupOffsets[g] = PointF(ox, oy)
            
            val scale = prefs.getFloat("scale_${pid}_$g", 1.0f)
            val spacing = prefs.getFloat("spacing_${pid}_$g", 1.0f)
            val opacity = prefs.getFloat("opacity_${pid}_$g", 1.0f)
            val visible = prefs.getBoolean("visible_${pid}_$g", true)
            val turbo = prefs.getBoolean("turbo_${pid}_$g", false)
            val analog = prefs.getBoolean("analog_${pid}_$g", false)
            
            buttonConfigs[g] = ButtonConfig(scale, spacing, opacity, visible, turbo, analog, null)
        }

        for (spec in profile.buttons) {
            buttons.add(ButtonDef(spec))
        }
        applyScales()
        invalidate()
    }

    private var safeInsetLeft = 0f
    private var safeInsetRight = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val insets = rootWindowInsets
        if (insets != null) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                safeInsetLeft = cutout.safeInsetLeft.toFloat()
                safeInsetRight = cutout.safeInsetRight.toFloat()
            }
        }
        applyScales()
    }

    private fun getOffset(group: String): PointF {
        return groupOffsets[group] ?: PointF(0f, 0f)
    }
    
    private fun getConfig(group: String): ButtonConfig {
        return buttonConfigs[group] ?: ButtonConfig()
    }

    fun applyScales() {
        val w = width.toFloat() - safeInsetLeft - safeInsetRight
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val baseRadius = h * 0.10f
        val joyR = h * 0.20f
        val baseSpacing = h * 0.15f
        
        var conf: ButtonConfig
        var off: PointF

        // L2
        conf = getConfig("L2"); off = getOffset("L2")
        buttons.find { it.group == "L2" }?.apply { cx = safeInsetLeft + w * 0.15f + off.x; cy = h * 0.15f + off.y; radius = baseRadius * 0.9f * conf.scale }
        
        // L1
        conf = getConfig("L1"); off = getOffset("L1")
        buttons.find { it.group == "L1" }?.apply { cx = safeInsetLeft + w * 0.30f + off.x; cy = h * 0.15f + off.y; radius = baseRadius * 0.9f * conf.scale }
        
        // R1
        conf = getConfig("R1"); off = getOffset("R1")
        buttons.find { it.group == "R1" }?.apply { cx = safeInsetLeft + w * 0.70f + off.x; cy = h * 0.15f + off.y; radius = baseRadius * 0.9f * conf.scale }
        
        // R2
        conf = getConfig("R2"); off = getOffset("R2")
        buttons.find { it.group == "R2" }?.apply { cx = safeInsetLeft + w * 0.85f + off.x; cy = h * 0.15f + off.y; radius = baseRadius * 0.9f * conf.scale }

        // Select
        conf = getConfig("Sel"); off = getOffset("Sel")
        buttons.find { it.group == "Sel" }?.apply { cx = safeInsetLeft + w * 0.40f + off.x; cy = h * 0.20f + off.y; radius = baseRadius * 0.7f * conf.scale }
        
        // Start
        conf = getConfig("St"); off = getOffset("St")
        buttons.find { it.group == "St" }?.apply { cx = safeInsetLeft + w * 0.60f + off.x; cy = h * 0.20f + off.y; radius = baseRadius * 0.7f * conf.scale }

        // Home
        conf = getConfig("Home"); off = getOffset("Home")
        buttons.find { it.group == "Home" }?.apply { cx = safeInsetLeft + w * 0.50f + off.x; cy = h * 0.35f + off.y; radius = baseRadius * 0.7f * conf.scale }

        // DPad
        conf = getConfig("DPad"); off = getOffset("DPad")
        val dpadCx = safeInsetLeft + w * 0.20f + off.x
        val dpadCy = h * 0.45f + off.y
        val dSpacing = baseSpacing * 0.8f * conf.spacing
        val dpadBtnR = baseRadius * 0.8f * conf.scale
        
        buttons.find { it.name == "U" && it.group == "DPad" }?.apply { cx = dpadCx; cy = dpadCy - dSpacing; radius = dpadBtnR }
        buttons.find { it.name == "D" && it.group == "DPad" }?.apply { cx = dpadCx; cy = dpadCy + dSpacing; radius = dpadBtnR }
        buttons.find { it.name == "L" && it.group == "DPad" }?.apply { cx = dpadCx - dSpacing; cy = dpadCy; radius = dpadBtnR }
        buttons.find { it.name == "R" && it.group == "DPad" }?.apply { cx = dpadCx + dSpacing; cy = dpadCy; radius = dpadBtnR }

        // ABXY
        conf = getConfig("ABXY"); off = getOffset("ABXY")
        val abxyCx = safeInsetLeft + w * 0.85f + off.x
        val abxyCy = h * 0.45f + off.y
        val aSpacing = baseSpacing * conf.spacing
        val aBtnR = baseRadius * conf.scale

        if (currentProfile.id == "nintendo") {
            buttons.find { it.name == "A" && it.group == "ABXY" }?.apply { cx = abxyCx + aSpacing; cy = abxyCy; radius = aBtnR } // Right
            buttons.find { it.name == "B" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy + aSpacing; radius = aBtnR } // Bottom
            buttons.find { it.name == "X" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy - aSpacing; radius = aBtnR } // Top
            buttons.find { it.name == "Y" && it.group == "ABXY" }?.apply { cx = abxyCx - aSpacing; cy = abxyCy; radius = aBtnR } // Left
        } else if (currentProfile.id == "xbox") {
            buttons.find { it.name == "B" && it.group == "ABXY" }?.apply { cx = abxyCx + aSpacing; cy = abxyCy; radius = aBtnR } // Right
            buttons.find { it.name == "A" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy + aSpacing; radius = aBtnR } // Bottom
            buttons.find { it.name == "Y" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy - aSpacing; radius = aBtnR } // Top
            buttons.find { it.name == "X" && it.group == "ABXY" }?.apply { cx = abxyCx - aSpacing; cy = abxyCy; radius = aBtnR } // Left
        } else if (currentProfile.id == "psp") {
            buttons.find { it.name == "Circle" && it.group == "ABXY" }?.apply { cx = abxyCx + aSpacing; cy = abxyCy; radius = aBtnR } // Right
            buttons.find { it.name == "Cross" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy + aSpacing; radius = aBtnR } // Bottom
            buttons.find { it.name == "Triangle" && it.group == "ABXY" }?.apply { cx = abxyCx; cy = abxyCy - aSpacing; radius = aBtnR } // Top
            buttons.find { it.name == "Square" && it.group == "ABXY" }?.apply { cx = abxyCx - aSpacing; cy = abxyCy; radius = aBtnR } // Left
        }

        // Left Joystick
        conf = getConfig("LeftJoy"); off = getOffset("LeftJoy")
        leftJoy.cx = safeInsetLeft + w * 0.20f + off.x
        leftJoy.cy = h * 0.75f + off.y
        leftJoy.baseRadius = joyR
        leftJoy.radius = joyR * conf.scale
        leftJoy.knobX = leftJoy.cx
        leftJoy.knobY = leftJoy.cy
        
        // Right Joystick
        conf = getConfig("RightJoy"); off = getOffset("RightJoy")
        rightJoy.cx = safeInsetLeft + w * 0.65f + off.x
        rightJoy.cy = h * 0.75f + off.y
        rightJoy.baseRadius = joyR
        rightJoy.radius = joyR * conf.scale
        rightJoy.knobX = rightJoy.cx
        rightJoy.knobY = rightJoy.cy

        buttons.find { it.name == "Menu" }?.apply { cx = safeInsetLeft + h * 0.15f; cy = h * 0.15f; radius = baseRadius * 0.8f }
        buttons.find { it.name == "Save" }?.apply { cx = safeInsetLeft + w - (h * 0.15f); cy = h * 0.15f; radius = baseRadius * 0.9f }
        buttons.find { it.name == "Cancel" }?.apply { cx = safeInsetLeft + w - (h * 0.35f); cy = h * 0.15f; radius = baseRadius * 0.9f }

        invalidate()
    }

    fun saveConfigState() {
        val editor = prefs.edit()
        val pid = currentProfile.id
        for ((group, pt) in groupOffsets) {
            editor.putFloat("offX_${pid}_$group", pt.x)
            editor.putFloat("offY_${pid}_$group", pt.y)
        }
        for ((group, config) in buttonConfigs) {
            editor.putFloat("scale_${pid}_$group", config.scale)
            editor.putFloat("spacing_${pid}_$group", config.spacing)
            editor.putFloat("opacity_${pid}_$group", config.opacity)
            editor.putBoolean("visible_${pid}_$group", config.visible)
            editor.putBoolean("turbo_${pid}_$group", config.turbo)
            editor.putBoolean("analog_${pid}_$group", config.analogTrigger)
        }
        editor.apply()
    }

    fun resetLayoutDefaults() {
        groupOffsets.clear()
        for (g in groupNames) {
            groupOffsets[g] = PointF(0f, 0f)
            buttonConfigs[g] = ButtonConfig()
        }
        val editor = prefs.edit()
        val pid = currentProfile.id
        for (g in groupNames) {
            editor.remove("offX_${pid}_$g")
            editor.remove("offY_${pid}_$g")
            editor.remove("scale_${pid}_$g")
            editor.remove("spacing_${pid}_$g")
            editor.remove("opacity_${pid}_$g")
            editor.remove("visible_${pid}_$g")
            editor.remove("turbo_${pid}_$g")
            editor.remove("analog_${pid}_$g")
        }
        editor.apply()
        applyScales()
        invalidate()
    }

    fun toggleTelemetry() {
        showTelemetry = !showTelemetry
        prefs.edit().putBoolean("showTelemetry", showTelemetry).apply()
        invalidate()
    }

    fun enterEditMode() {
        isEditMode = true
        originalGroupOffsets.clear()
        for (g in groupNames) {
            originalGroupOffsets[g] = PointF(groupOffsets[g]?.x ?: 0f, groupOffsets[g]?.y ?: 0f)
        }
        
        val state = udpSender?.state ?: bluetoothSender?.state
        if (state != null) {
            if (!isGyroEnabled) state.lx = 0
            state.ly = 0
            state.rx = 0; state.ry = 0
            state.buttons = 0; state.lt = 0; state.rt = 0
        }
        invalidate()
    }
    
    fun saveEditMode() {
        isEditMode = false
        draggingGroup = null
        saveConfigState()
        onEditModeExited?.invoke()
        invalidate()
    }

    fun cancelEditMode() {
        isEditMode = false
        draggingGroup = null
        for ((group, pt) in originalGroupOffsets) {
            groupOffsets[group] = PointF(pt.x, pt.y)
        }
        onEditModeExited?.invoke()
        applyScales()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (!isEditMode) {
                val cx = width / 2f
                val cy = height * 0.1f
                val r = Math.min(width, height) * 0.05f
                if (Math.hypot((x - cx).toDouble(), (y - cy).toDouble()) < r * 1.5f) {
                    isInputPaused = !isInputPaused
                    updateButtonState()
                    invalidate()
                    return true
                }
            }
        }

        if (isEditMode) {
            return handleEditModeTouch(event, action, pointerId, x, y)
        }

        val upIndex = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) event.actionIndex else -1
        
        for (btn in buttons) {
            btn.isPressed = false
            btn.pointerId = -1
            btn.analogValue = 0
        }

        var isLeftJoyActive = false
        var isRightJoyActive = false

        for (i in 0 until event.pointerCount) {
            if (i == upIndex) continue

            val pId = event.getPointerId(i)
            val pX = event.getX(i)
            val pY = event.getY(i)

            if (pId == leftJoy.pointerId || (leftJoy.pointerId == -1 && leftJoy.contains(pX, pY) && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN))) {
                leftJoy.pointerId = pId
                updateJoystick(leftJoy, pX, pY, isLeft = true)
                isLeftJoyActive = true
                continue
            }
            if (currentProfile.hasSecondStick && (pId == rightJoy.pointerId || (rightJoy.pointerId == -1 && rightJoy.contains(pX, pY) && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)))) {
                rightJoy.pointerId = pId
                updateJoystick(rightJoy, pX, pY, isLeft = false)
                isRightJoyActive = true
                continue
            }

            for (btn in buttons) {
                if (btn.isOverlayUI) continue
                val config = getConfig(btn.group)
                if (!config.visible) continue
                
                if (btn.contains(pX, pY)) {
                    btn.isPressed = true
                    btn.pointerId = pId
                    if (btn.isTrigger && config.analogTrigger) {
                        val dist = hypot((pX - btn.cx).toDouble(), (pY - btn.cy).toDouble()).toFloat()
                        val maxDist = btn.radius * 2.5f
                        val pressure = (dist / maxDist).coerceIn(0f, 1f)
                        btn.analogValue = (pressure * 255).toInt().toByte()
                    } else {
                        btn.analogValue = (-1).toByte()
                    }
                }
            }
        }

        if (!isLeftJoyActive && leftJoy.pointerId != -1) {
            leftJoy.pointerId = -1
            updateJoystick(leftJoy, leftJoy.cx, leftJoy.cy, true)
        }
        if (!isRightJoyActive && rightJoy.pointerId != -1) {
            rightJoy.pointerId = -1
            updateJoystick(rightJoy, rightJoy.cx, rightJoy.cy, false)
        }

        updateButtonState()
        invalidate()
        
        return true
    }

    private fun handleEditModeTouch(event: MotionEvent, action: Int, pointerId: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (draggingGroup == null) {
                    didDrag = false
                    var targetGroup: String? = null
                    if (currentProfile.hasSecondStick && leftJoy.contains(x, y)) targetGroup = leftJoy.group
                    else if (currentProfile.hasSecondStick && rightJoy.contains(x, y)) targetGroup = rightJoy.group
                    else {
                        for (btn in buttons) {
                            if (!btn.isOverlayUI && btn.contains(x, y)) {
                                targetGroup = btn.group
                                break
                            }
                        }
                    }
                    if (targetGroup != null) {
                        draggingGroup = targetGroup
                        dragPointerId = pointerId
                        dragStartX = x
                        dragStartY = y
                        val pt = groupOffsets[targetGroup] ?: PointF(0f, 0f)
                        dragInitialOffsetX = pt.x
                        dragInitialOffsetY = pt.y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val group = draggingGroup
                if (group != null && dragPointerId != -1) {
                    val pIndex = event.findPointerIndex(dragPointerId)
                    if (pIndex != -1) {
                        val px = event.getX(pIndex)
                        val py = event.getY(pIndex)
                        val dx = px - dragStartX
                        val dy = py - dragStartY
                        if (hypot(dx.toDouble(), dy.toDouble()) > 10) {
                            didDrag = true
                            groupOffsets[group] = PointF(dragInitialOffsetX + dx, dragInitialOffsetY + dy)
                            applyScales()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == dragPointerId) {
                    if (!didDrag && draggingGroup != null) {
                        onButtonEditRequested?.invoke(draggingGroup!!)
                    }
                    draggingGroup = null
                    dragPointerId = -1
                    invalidate()
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    // Fallback to clear if all pointers are up
                    draggingGroup = null
                    dragPointerId = -1
                    invalidate()
                }
            }
        }
        return true
    }

    private fun updateJoystick(joy: Joystick, x: Float, y: Float, isLeft: Boolean) {
        if (isEditMode) return
        val state = udpSender?.state ?: bluetoothSender?.state ?: return
        
        var dx = x - joy.cx
        var dy = y - joy.cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist > joy.radius) { dx = (dx / dist) * joy.radius; dy = (dy / dist) * joy.radius }
        joy.knobX = joy.cx + dx; joy.knobY = joy.cy + dy
        val normX = dx / joy.radius
        val normY = -dy / joy.radius 
        
        if (!isInputPaused) {
            val deadzone = 0.12f
            val magnitude = hypot(normX.toDouble(), normY.toDouble()).toFloat()
            val outX = if (magnitude < deadzone) 0f else (normX * ((magnitude - deadzone) / (1f - deadzone)) / magnitude)
            val outY = if (magnitude < deadzone) 0f else (normY * ((magnitude - deadzone) / (1f - deadzone)) / magnitude)
            
            if (isLeft) {
                if (!isGyroEnabled) state.lx = (outX * 32767).toInt().toShort()
                state.ly = (outY * 32767).toInt().toShort()
            } else {
                state.rx = (outX * 32767).toInt().toShort(); state.ry = (outY * 32767).toInt().toShort()
            }
        } else {
            if (isLeft) {
                if (!isGyroEnabled) state.lx = 0
                state.ly = 0
            } else {
                state.rx = 0; state.ry = 0
            }
        }
        invalidate()
    }

    private fun updateButtonState() {
        val state = udpSender?.state ?: bluetoothSender?.state ?: return
        var mask = 0; var lt: Byte = 0; var rt: Byte = 0
        if (!isInputPaused) {
            for (btn in buttons) {
                if (btn.isPressed) {
                    val config = buttonConfigs[btn.group] ?: ButtonConfig()
                    if (config.turbo && !turboState) continue
                    if (btn.isTrigger) {
                        if (btn.isL2) lt = btn.analogValue else rt = btn.analogValue
                    } else if (!btn.isOverlayUI) {
                        mask = mask or btn.bit
                    }
                }
            }
        }
        state.buttons = mask; state.lt = lt; state.rt = rt
    }

    private fun drawPauseToggle(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * 0.1f
        val r = Math.min(width, height) * 0.05f
        paint.color = if (isInputPaused) Color.parseColor("#FF5C5C") else GamepadColors.neutralBase
        paint.style = Paint.Style.FILL; paint.alpha = 200; canvas.drawCircle(cx, cy, r, paint)
        paint.color = GamepadColors.neutralText; paint.alpha = 255; paint.style = Paint.Style.STROKE; paint.strokeWidth = r * 0.2f
        if (isInputPaused) {
            val path = Path()
            path.moveTo(cx - r*0.2f, cy - r*0.4f); path.lineTo(cx + r*0.4f, cy); path.lineTo(cx - r*0.2f, cy + r*0.4f); path.close()
            paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
        } else {
            canvas.drawLine(cx - r*0.2f, cy - r*0.3f, cx - r*0.2f, cy + r*0.3f, paint)
            canvas.drawLine(cx + r*0.2f, cy - r*0.3f, cx + r*0.2f, cy + r*0.3f, paint)
        }
    }

    private fun drawDpadArrow(canvas: Canvas, name: String, cx: Float, cy: Float, r: Float, baseColor: Int, topColor: Int, a: Int) {
        val path = Path()
        val hw = r * 0.55f          // half width (slimmer)
        val outer = r * 1.0f        // flat outer edge (taller)
        val innerTip = r * 0.9f     // pointy inner tip
        val innerShoulder = r * 0.2f // where the point starts
        
        when (name) {
            "U" -> { path.moveTo(cx - hw, cy - outer); path.lineTo(cx + hw, cy - outer); path.lineTo(cx + hw, cy + innerShoulder); path.lineTo(cx, cy + innerTip); path.lineTo(cx - hw, cy + innerShoulder) }
            "D" -> { path.moveTo(cx - hw, cy + outer); path.lineTo(cx + hw, cy + outer); path.lineTo(cx + hw, cy - innerShoulder); path.lineTo(cx, cy - innerTip); path.lineTo(cx - hw, cy - innerShoulder) }
            "L" -> { path.moveTo(cx - outer, cy - hw); path.lineTo(cx - outer, cy + hw); path.lineTo(cx + innerShoulder, cy + hw); path.lineTo(cx + innerTip, cy); path.lineTo(cx + innerShoulder, cy - hw) }
            "R" -> { path.moveTo(cx + outer, cy - hw); path.lineTo(cx + outer, cy + hw); path.lineTo(cx - innerShoulder, cy + hw); path.lineTo(cx - innerTip, cy); path.lineTo(cx - innerShoulder, cy - hw) }
        }
        path.close()
        paint.pathEffect = CornerPathEffect(r * 0.15f)
        
        // Base fill
        paint.color = baseColor
        paint.alpha = a
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        
        // Top border (stroke)
        paint.color = topColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        
        // Inner engraved arrow direction indicator
        paint.style = Paint.Style.FILL
        paint.color = topColor
        paint.alpha = (a * 0.6f).toInt()
        val indPath = Path()
        val s = r * 0.25f
        val offset = r * 0.5f
        when (name) {
            "U" -> { indPath.moveTo(cx, cy - offset - s); indPath.lineTo(cx + s, cy - offset + s*0.5f); indPath.lineTo(cx - s, cy - offset + s*0.5f) }
            "D" -> { indPath.moveTo(cx, cy + offset + s); indPath.lineTo(cx + s, cy + offset - s*0.5f); indPath.lineTo(cx - s, cy + offset - s*0.5f) }
            "L" -> { indPath.moveTo(cx - offset - s, cy); indPath.lineTo(cx - offset + s*0.5f, cy + s); indPath.lineTo(cx - offset + s*0.5f, cy - s) }
            "R" -> { indPath.moveTo(cx + offset + s, cy); indPath.lineTo(cx + offset - s*0.5f, cy + s); indPath.lineTo(cx + offset - s*0.5f, cy - s) }
        }
        indPath.close()
        canvas.drawPath(indPath, paint)
    }

    private fun drawPspShape(canvas: Canvas, name: String, cx: Float, cy: Float, r: Float, color: Int, a: Int) {
        paint.color = color; paint.alpha = a; paint.strokeWidth = r * 0.15f; paint.style = Paint.Style.STROKE
        val s = r * 0.5f
        when (name) {
            "Triangle" -> { val path = Path(); path.moveTo(cx, cy - s); path.lineTo(cx - s * 0.866f, cy + s * 0.5f); path.lineTo(cx + s * 0.866f, cy + s * 0.5f); path.close(); canvas.drawPath(path, paint) }
            "Circle" -> canvas.drawCircle(cx, cy, s, paint)
            "Cross" -> { canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint); canvas.drawLine(cx - s, cy + s, cx + s, cy - s, paint) }
            "Square" -> canvas.drawRect(cx - s, cy - s, cx + s, cy + s, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B0C10"))
        if (isEditMode) {
            val w = width.toFloat(); val h = height.toFloat(); val gridSize = h / 10f
            var i = 0f; while (i < w) { canvas.drawLine(i, 0f, i, h, gridPaint); i += gridSize }
            var j = 0f; while (j < h) { canvas.drawLine(0f, j, w, j, gridPaint); j += gridSize }
        }
        if (getConfig("LeftJoy").visible || isEditMode) drawJoystick(canvas, leftJoy, getConfig("LeftJoy"))
        if (currentProfile.hasSecondStick && (getConfig("RightJoy").visible || isEditMode)) drawJoystick(canvas, rightJoy, getConfig("RightJoy"))
        
        for (btn in buttons) {
            val config = getConfig(btn.group)
            if (!config.visible && !isEditMode) continue
            val alphaInt = (config.opacity * 255).toInt()
            val isBtnPressed = btn.isPressed && !isEditMode
            
            if (isEditMode && draggingGroup == btn.group) {
                paint.color = getPlayerColor(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                paint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                if (btn.isShoulder) canvas.drawRoundRect(RectF(btn.cx - btn.radius * 1.65f, btn.cy - btn.radius * 0.9f, btn.cx + btn.radius * 1.65f, btn.cy + btn.radius * 0.9f), btn.radius * 0.3f, btn.radius * 0.3f, paint)
                else if (btn.isDpad) canvas.drawCircle(btn.cx, btn.cy, btn.radius * 1.25f, paint)
                else canvas.drawCircle(btn.cx, btn.cy, btn.radius * 1.15f, paint)
                paint.pathEffect = null; paint.style = Paint.Style.FILL
            }

            var baseColor = GamepadColors.neutralBase
            var topColor = GamepadColors.neutralTop
            var textColor = GamepadColors.neutralText

            if (!isBtnPressed) {
                when (btn.name) {
                    "A" -> { baseColor = GamepadColors.aBase; topColor = GamepadColors.aTop; textColor = GamepadColors.aText }
                    "B" -> { baseColor = GamepadColors.bBase; topColor = GamepadColors.bTop; textColor = GamepadColors.bText }
                    "X" -> { baseColor = GamepadColors.xBase; topColor = GamepadColors.xTop; textColor = GamepadColors.xText }
                    "Y" -> { baseColor = GamepadColors.yBase; topColor = GamepadColors.yTop; textColor = GamepadColors.yText }
                }
            } else {
                when (btn.name) {
                    "A" -> { baseColor = GamepadColors.aTop; textColor = GamepadColors.aBase }
                    "B" -> { baseColor = GamepadColors.bTop; textColor = GamepadColors.bBase }
                    "X" -> { baseColor = GamepadColors.xTop; textColor = GamepadColors.xBase }
                    "Y" -> { baseColor = GamepadColors.yTop; textColor = GamepadColors.yBase }
                    else -> { baseColor = getPlayerColor(); textColor = GamepadColors.neutralBase }
                }
            }

            config.colorOverride?.let { c ->
                baseColor = if (isBtnPressed) manipulateColor(c, 1.2f) else c
                topColor = if (isBtnPressed) c else manipulateColor(c, 1.2f)
            }

            paint.style = Paint.Style.FILL
            paint.color = baseColor
            paint.alpha = alphaInt

            if (btn.isShoulder) {
                val rect = RectF(btn.cx - btn.radius * 1.5f, btn.cy - btn.radius * 0.75f, btn.cx + btn.radius * 1.5f, btn.cy + btn.radius * 0.75f)
                canvas.drawRoundRect(rect, btn.radius * 0.3f, btn.radius * 0.3f, paint)
                
                paint.style = Paint.Style.STROKE
                paint.color = topColor
                paint.alpha = alphaInt
                paint.strokeWidth = 3f
                canvas.drawRoundRect(rect, btn.radius * 0.3f, btn.radius * 0.3f, paint)
            } else if (btn.isDpad) {
                drawDpadArrow(canvas, btn.name, btn.cx, btn.cy, btn.radius, baseColor, topColor, alphaInt)
            } else {
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, paint)
                
                paint.style = Paint.Style.STROKE
                paint.color = topColor
                paint.alpha = alphaInt
                paint.strokeWidth = 3f
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, paint)
            }
            
            if (currentProfile.id == "psp" && (btn.name == "Triangle" || btn.name == "Circle" || btn.name == "Cross" || btn.name == "Square")) {
                val shapeColor = if (isBtnPressed) GamepadColors.neutralBase else GamepadColors.neutralText
                drawPspShape(canvas, btn.name, btn.cx, btn.cy, btn.radius, shapeColor, alphaInt)
            } else if (btn.name == "Home") {
                val s = btn.radius * 0.5f
                val path = Path()
                path.moveTo(btn.cx, btn.cy - s)
                path.lineTo(btn.cx - s, btn.cy)
                path.lineTo(btn.cx - s * 0.6f, btn.cy)
                path.lineTo(btn.cx - s * 0.6f, btn.cy + s)
                path.lineTo(btn.cx + s * 0.6f, btn.cy + s)
                path.lineTo(btn.cx + s * 0.6f, btn.cy)
                path.lineTo(btn.cx + s, btn.cy)
                path.close()
                
                // Door inside
                path.moveTo(btn.cx - s * 0.25f, btn.cy + s)
                path.lineTo(btn.cx - s * 0.25f, btn.cy + s * 0.3f)
                path.lineTo(btn.cx + s * 0.25f, btn.cy + s * 0.3f)
                path.lineTo(btn.cx + s * 0.25f, btn.cy + s)
                
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.WHITE
                paint.alpha = alphaInt
                canvas.drawPath(path, paint)
                paint.strokeJoin = Paint.Join.MITER
            } else if (!btn.isDpad) {
                paint.style = Paint.Style.FILL
                paint.color = textColor
                paint.alpha = alphaInt
                
                // Make text look like premium physical button labels
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                if (!isBtnPressed) {
                    paint.setShadowLayer(3f, 0f, 2f, android.graphics.Color.argb(120, 0, 0, 0))
                }
                
                val textSize = if (btn.name.length > 2) btn.radius * 0.5f else btn.radius * 0.65f
                paint.textSize = textSize
                paint.textAlign = Paint.Align.CENTER
                val textOffset = (paint.descent() + paint.ascent()) / 2
                canvas.drawText(btn.name, btn.cx, btn.cy - textOffset, paint)
                
                // Reset paint
                paint.clearShadowLayer()
                paint.typeface = android.graphics.Typeface.DEFAULT
            }
        }

        if (calibrationFlashAlpha > 0) {
            paint.style = Paint.Style.FILL
            paint.color = getPlayerColor()
            paint.alpha = (calibrationFlashAlpha * 100).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            calibrationFlashAlpha -= 0.05f
            if (calibrationFlashAlpha < 0) calibrationFlashAlpha = 0f
            invalidate()
        }

        if (isGyroEnabled && !isEditMode) {
            drawGyroVisuals(canvas)
        }

        if (showTelemetry && !isEditMode) {
            drawTelemetryHud(canvas)
        }
        drawPauseToggle(canvas)
    }

    private fun drawTelemetryHud(canvas: Canvas) {
        val state = udpSender?.state ?: return
        paint.color = getPlayerColor()
        paint.textSize = height * 0.04f
        paint.textAlign = Paint.Align.CENTER
        paint.style = Paint.Style.FILL

        val hudX = width / 2f
        val hudY = height * 0.05f

        val maskHex = state.buttons.toString(16).padStart(4, '0').uppercase()
        val text1 = "[TELEMETRY_LINK_ACTIVE] BTN_MASK:0x$maskHex"
        val text2 = "LX:${state.lx.toString().padStart(6, ' ')} LY:${state.ly.toString().padStart(6, ' ')} " +
                    "RX:${state.rx.toString().padStart(6, ' ')} RY:${state.ry.toString().padStart(6, ' ')}"
        val text3 = "LT:${state.lt.toString().padStart(4, ' ')} RT:${state.rt.toString().padStart(4, ' ')}"

        canvas.drawText(text1, hudX, hudY, paint)
        canvas.drawText(text2, hudX, hudY + paint.textSize * 1.5f, paint)
        canvas.drawText(text3, hudX, hudY + paint.textSize * 3.0f, paint)
    }

    private fun drawGyroVisuals(canvas: Canvas) {
        if (gyroTargetMode == 0) {
            // 1D Horizontal Bar (X-Only)
            val cx = width / 2f
            val cy = height * 0.90f
            val barWidth = width * 0.3f
            val barHeight = height * 0.015f
            
            paint.style = Paint.Style.FILL
            paint.color = GamepadColors.neutralTop
            paint.alpha = 100
            canvas.drawRoundRect(cx - barWidth/2, cy - barHeight/2, cx + barWidth/2, cy + barHeight/2, barHeight/2, barHeight/2, paint)
            
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawRect(cx - 2f, cy - barHeight, cx + 2f, cy + barHeight, paint)
            
            paint.color = getPlayerColor()
            paint.alpha = 200
            val fillLength = Math.abs(gyroTiltX) * (barWidth / 2f)
            if (gyroTiltX > 0) {
                canvas.drawRoundRect(cx, cy - barHeight/2, cx + fillLength, cy + barHeight/2, barHeight/2, barHeight/2, paint)
            } else {
                canvas.drawRoundRect(cx - fillLength, cy - barHeight/2, cx, cy + barHeight/2, barHeight/2, barHeight/2, paint)
            }
            
            paint.textSize = height * 0.035f
            paint.textAlign = Paint.Align.CENTER
            paint.color = getPlayerColor()
            paint.alpha = 255
            canvas.drawText(String.format("STEER: %.0f%%", gyroTiltX * 100f), cx, cy - barHeight * 1.5f, paint)
            
            if (!isInputPaused) {
                leftJoy.knobX = leftJoy.cx + (gyroTiltX * leftJoy.radius)
            }
        } else {
            // 2D Circle Crosshair (X & Y)
            val cx = width / 2f
            val cy = height * 0.88f
            val radius = height * 0.08f
            
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = getPlayerColor()
            paint.alpha = 100
            canvas.drawCircle(cx, cy, radius, paint)
            
            paint.alpha = 50
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
            
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#8A2BE2")
            paint.alpha = 255
            canvas.drawCircle(cx + gyroTiltX * radius, cy + gyroTiltY * radius, radius * 0.2f, paint)
            
            paint.textSize = height * 0.03f
            paint.textAlign = Paint.Align.CENTER
            val targetName = if (gyroTargetMode == 1) "LEFT JOYSTICK" else "RIGHT JOYSTICK"
            canvas.drawText(String.format("%s [ X: %.0f%% | Y: %.0f%% ]", targetName, gyroTiltX * 100f, -gyroTiltY * 100f), cx, cy - radius * 1.3f, paint)
            
            if (!isInputPaused) {
                if (gyroTargetMode == 1) {
                    leftJoy.knobX = leftJoy.cx + (gyroTiltX * leftJoy.radius)
                    leftJoy.knobY = leftJoy.cy + (gyroTiltY * leftJoy.radius)
                } else if (gyroTargetMode == 2) {
                    rightJoy.knobX = rightJoy.cx + (gyroTiltX * rightJoy.radius)
                    rightJoy.knobY = rightJoy.cy + (gyroTiltY * rightJoy.radius)
                }
            }
        }
    }

    private fun drawJoystick(canvas: Canvas, joy: Joystick, config: ButtonConfig) {
        if (isEditMode && draggingGroup == joy.group) {
            paint.color = Color.parseColor("#8A2BE2")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            val pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
            paint.pathEffect = pathEffect
            canvas.drawCircle(joy.cx, joy.cy, joy.radius * 1.15f, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
        }

        val isActive = joy.knobX != joy.cx || joy.knobY != joy.cy && !isEditMode
        val alphaInt = (config.opacity * 255).toInt()

        paint.color = GamepadColors.neutralBase
        paint.alpha = alphaInt
        paint.style = Paint.Style.FILL
        canvas.drawCircle(joy.cx, joy.cy, joy.radius, paint)

        // Concentric base ring for texture
        paint.color = GamepadColors.neutralTop
        paint.alpha = (alphaInt * 0.3f).toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(joy.cx, joy.cy, joy.radius * 0.6f, paint)

        // Outer base border
        paint.color = if (isActive) Color.parseColor("#45A29E") else GamepadColors.neutralTop
        paint.alpha = alphaInt
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(joy.cx, joy.cy, joy.radius, paint)
        
        // Knob base
        paint.color = if (isActive) Color.parseColor("#8A2BE2") else GamepadColors.neutralTop
        paint.alpha = alphaInt
        paint.style = Paint.Style.FILL
        canvas.drawCircle(joy.knobX, joy.knobY, joy.radius * 0.45f, paint)
        
        // Knob inner highlight/gradient ring for 3D effect
        paint.color = Color.parseColor("#0B0C10")
        paint.alpha = (alphaInt * 0.6f).toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(joy.knobX, joy.knobY, joy.radius * 0.25f, paint)
        
        // Knob outer border
        paint.color = GamepadColors.neutralBase
        paint.alpha = alphaInt
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(joy.knobX, joy.knobY, joy.radius * 0.45f, paint)
    }
    private fun manipulateColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = Math.round(Color.red(color) * factor)
        val g = Math.round(Color.green(color) * factor)
        val b = Math.round(Color.blue(color) * factor)
        return Color.argb(a, Math.min(r.toInt(), 255), Math.min(g.toInt(), 255), Math.min(b.toInt(), 255))
    }
}
