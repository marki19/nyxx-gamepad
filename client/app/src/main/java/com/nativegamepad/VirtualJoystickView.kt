package com.nativegamepad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class VirtualJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onDirectionChanged: ((degree: Int) -> Unit)? = null
    var onReleased: (() -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f

    private var knobX = 0f
    private var knobY = 0f

    // Throttling, Quantization (15°), and Keep-Alive (100ms)
    private var isActivelySteering = false
    private var currentQuantizedDegree: Int? = null
    private var lastSentDegree = -999
    private var lastSendTime = 0L

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (isActivelySteering && currentQuantizedDegree != null) {
                val now = SystemClock.elapsedRealtime()
                val deg = currentQuantizedDegree ?: return
                if (deg != lastSentDegree && (now - lastSendTime >= 50L)) {
                    lastSendTime = now
                    lastSentDegree = deg
                    onDirectionChanged?.invoke(deg)
                } else if (now - lastSendTime >= 100L) {
                    lastSendTime = now
                    onDirectionChanged?.invoke(deg)
                }
                postDelayed(this, 15L)
            }
        }
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#151922")
    }

    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#45A29E")
    }

    private val deadzoneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#2266FCF1")
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FCF1")
    }

    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3345A29E")
    }

    private val knobCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0B0C10")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val minDim = min(w, h).toFloat()
        baseRadius = (minDim / 2f) * 0.85f
        knobRadius = baseRadius * 0.38f

        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseRadius <= 0) return

        // 1. Outer base circle
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, baseBorderPaint)

        // 2. Inner deadzone ring (20% of radius)
        canvas.drawCircle(centerX, centerY, baseRadius * 0.20f, deadzoneBorderPaint)

        // 3. Knob shadow & glow
        canvas.drawCircle(knobX, knobY, knobRadius * 1.15f, knobShadowPaint)

        // 4. Knob
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius * 0.35f, knobCenterDotPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                updateKnobPosition(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateKnobPosition(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                isActivelySteering = false
                currentQuantizedDegree = null
                lastSentDegree = -999
                removeCallbacks(keepAliveRunnable)
                animateKnobToCenter()
                onReleased?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnobPosition(touchX: Float, touchY: Float) {
        val dx = touchX - centerX
        val dy = touchY - centerY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        val maxRadius = baseRadius - knobRadius * 0.2f
        if (dist > maxRadius) {
            knobX = centerX + (dx / dist) * maxRadius
            knobY = centerY + (dy / dist) * maxRadius
        } else {
            knobX = touchX
            knobY = touchY
        }

        // 20% Deadzone: if less than ~20% from center, treat as released and send "%S"
        val deadzone = maxRadius * 0.20f
        if (dist > deadzone) {
            val rad = atan2(-dy.toDouble(), dx.toDouble())
            val rawDegree = Math.toDegrees(rad).roundToInt().coerceIn(-180, 180)
            // Quantize to nearest 15 degrees
            val quantized = (Math.round(rawDegree / 15.0) * 15).toInt().coerceIn(-180, 180)
            currentQuantizedDegree = quantized

            val now = SystemClock.elapsedRealtime()
            if (!isActivelySteering) {
                isActivelySteering = true
                post(keepAliveRunnable)
            }

            // Never send more than one joystick packet per 50 ms unless angle changed and window open
            if (quantized != lastSentDegree && (now - lastSendTime >= 50L)) {
                lastSendTime = now
                lastSentDegree = quantized
                onDirectionChanged?.invoke(quantized)
            }
        } else {
            if (isActivelySteering) {
                isActivelySteering = false
                currentQuantizedDegree = null
                lastSentDegree = -999
                removeCallbacks(keepAliveRunnable)
                onReleased?.invoke()
            }
        }

        invalidate()
    }

    private fun animateKnobToCenter() {
        val startX = knobX
        val startY = knobY
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                knobX = startX + (centerX - startX) * frac
                knobY = startY + (centerY - startY) * frac
                invalidate()
            }
        }
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(keepAliveRunnable)
    }
}
