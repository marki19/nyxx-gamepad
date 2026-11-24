package com.nativegamepad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Realistic racing foot pedal view for robot car and vehicle control,
 * modeled directly after authentic racing pedals with metallic face plate,
 * raised non-slip rubber traction studs, 3D depth shadows, and tactile depression physics.
 */
class RacingPedalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PedalType {
        GAS,
        BRAKE
    }

    var pedalType: PedalType = PedalType.GAS
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    var onPedalStateChanged: ((isDown: Boolean) -> Unit)? = null

    var isDepressed = false
        private set

    // Animation progress 0f (rest) to 1f (fully depressed)
    private var depressionProgress = 0f
    private var animator: ValueAnimator? = null

    // Geometry
    private val pedalRect = RectF()
    private val baseRect = RectF()
    private val studRect = RectF()
    private val studShadowRect = RectF()
    private val studHighlightRect = RectF()

    // Paints
    private val basePlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0C0E14")
    }

    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1F2833")
    }

    private val pedalPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pedalBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pedalGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val studBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#151A22")
    }

    private val studShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80000000")
    }

    private val studHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4DFFFFFF")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
        isFakeBoldText = true
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        color = Color.parseColor("#8B949E")
    }

    private var accentColor = Color.parseColor("#66FCF1")
    private var accentColorDim = Color.parseColor("#3366FCF1")

    init {
        updateColors()
    }

    private fun updateColors() {
        if (pedalType == PedalType.GAS) {
            accentColor = Color.parseColor("#66FCF1")
            accentColorDim = Color.parseColor("#3366FCF1")
            titlePaint.color = Color.parseColor("#66FCF1")
        } else {
            accentColor = Color.parseColor("#FF5C5C")
            accentColorDim = Color.parseColor("#33FF5C5C")
            titlePaint.color = Color.parseColor("#FF5C5C")
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pedalPlatePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#28323F"),
                Color.parseColor("#1B222D"),
                Color.parseColor("#12161E")
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padding = 4f
        baseRect.set(padding, padding, w - padding, h - padding)

        // 1. Slanted base mounting floorplate with diamond facet bevel
        canvas.drawRoundRect(baseRect, 14f, 14f, basePlatePaint)
        canvas.drawRoundRect(baseRect, 14f, 14f, baseBorderPaint)

        // 2. Interactive pedal depression transformation
        canvas.save()
        val maxDepressionY = h * 0.05f
        val currentDepressionY = depressionProgress * maxDepressionY
        val scale = 1f - (depressionProgress * 0.04f)

        canvas.translate(0f, currentDepressionY)
        canvas.scale(scale, scale, w / 2f, h / 2f)

        // Pedal plate bounds
        val horizMargin = if (pedalType == PedalType.BRAKE) w * 0.07f else w * 0.12f
        val topMargin = h * 0.06f
        val bottomMargin = h * 0.06f
        pedalRect.set(horizMargin, topMargin, w - horizMargin, h - bottomMargin)

        // Drop shadow behind pedal plate
        val shadowRect = RectF(pedalRect)
        shadowRect.offset(0f, 6f * (1f - depressionProgress * 0.5f))
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
        }
        canvas.drawRoundRect(shadowRect, 12f, 12f, shadowPaint)

        // 3. Pedal metallic face plate
        canvas.drawRoundRect(pedalRect, 12f, 12f, pedalPlatePaint)

        // 4. Bevel & Cyber Accent Border
        pedalBevelPaint.color = Color.parseColor("#2F3B4B")
        canvas.drawRoundRect(pedalRect, 12f, 12f, pedalBevelPaint)

        val glowColor = if (depressionProgress > 0.05f) accentColor else accentColorDim
        pedalGlowPaint.color = glowColor
        val innerGlowRect = RectF(pedalRect)
        innerGlowRect.inset(3f, 3f)
        canvas.drawRoundRect(innerGlowRect, 10f, 10f, pedalGlowPaint)

        // 5. Studded Non-Slip Grip Dots (2 columns, 4 rows for brake, 5 rows for gas)
        val rows = if (pedalType == PedalType.BRAKE) 4 else 5
        val cols = 2

        val studAreaTop = pedalRect.top + pedalRect.height() * 0.12f
        val studAreaBottom = pedalRect.bottom - pedalRect.height() * 0.32f
        val studAreaHeight = studAreaBottom - studAreaTop

        val studAreaLeft = pedalRect.left + pedalRect.width() * 0.22f
        val studAreaRight = pedalRect.right - pedalRect.width() * 0.22f
        val studAreaWidth = studAreaRight - studAreaLeft

        val studRadius = min(pedalRect.width() * 0.07f, 14f)

        for (r in 0 until rows) {
            val cy = studAreaTop + (studAreaHeight / (rows - 1)) * r
            for (c in 0 until cols) {
                val cx = studAreaLeft + (studAreaWidth / (cols - 1)) * c

                // Stud drop shadow
                canvas.drawCircle(cx, cy + 2f, studRadius, studShadowPaint)
                // Stud body
                canvas.drawCircle(cx, cy, studRadius, studBodyPaint)
                // Stud metallic bevel ring
                canvas.drawCircle(cx, cy, studRadius, baseBorderPaint)
                // Stud top specular highlight
                canvas.drawCircle(cx - studRadius * 0.25f, cy - studRadius * 0.25f, studRadius * 0.35f, studHighlightPaint)
            }
        }

        // 6. Engraved Labels
        val textCenterX = pedalRect.centerX()
        val textBaseY = pedalRect.bottom - pedalRect.height() * 0.14f

        titlePaint.textSize = pedalRect.width() * 0.16f
        subtitlePaint.textSize = pedalRect.width() * 0.10f

        val title = if (pedalType == PedalType.GAS) "GAS" else "BRAKE"
        val subtitle = if (pedalType == PedalType.GAS) "DRIVE" else "REV"

        canvas.drawText(title, textCenterX, textBaseY, titlePaint)
        canvas.drawText(subtitle, textCenterX, textBaseY + subtitlePaint.textSize * 1.25f, subtitlePaint)

        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isDepressed = true
                animateDepression(1f)
                onPedalStateChanged?.invoke(true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                isDepressed = false
                animateDepression(0f)
                onPedalStateChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateDepression(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(depressionProgress, target).apply {
            duration = if (target > 0.5f) 40L else 140L
            if (target < 0.5f) {
                interpolator = OvershootInterpolator(1.4f)
            }
            addUpdateListener { anim ->
                depressionProgress = anim.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }
}
