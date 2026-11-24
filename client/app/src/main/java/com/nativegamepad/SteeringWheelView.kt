package com.nativegamepad

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * Interactive Racing Steering Wheel View for robot car and vehicle control,
 * modeled directly after authentic ergonomic racing wheels with textured contour grips,
 * 12 o'clock center stripe, rear paddle shifters, multi-button spoke layout (L2, R2, D-pad),
 * and center circular 'N' badge.
 */
class SteeringWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onAngleChanged: ((angleDegrees: Float) -> Unit)? = null
    var onReleased: (() -> Unit)? = null

    // Steering bounds (degrees)
    val maxSteerAngle = 90f
    private val deadzoneAngle = 4f

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private var hubRadius = 0f

    // Current steering rotation angle in degrees (-90f full left, 0f center, +90f full right)
    var currentAngle = 0f
        private set

    // Touch tracking state
    private var isSteering = false
    private var touchStartAngle = 0f
    private var wheelStartAngle = 0f
    private var lastSentAngle = -999f
    private var lastSendTime = 0L

    // Paints
    private val rimBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#12161F")
    }

    private val rimOuterBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2B3645")
    }

    private val rimInnerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#3B4B5E")
    }

    private val gripContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1B232E")
    }

    private val gripStitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#263242")
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6EDF3")
    }

    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#0B0C10")
    }

    private val paddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#18202A")
    }

    private val paddleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#45A29E")
    }

    private val spokePlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#18202B")
    }

    private val spokeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#2D3B4C")
    }

    private val spokeAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#3366FCF1")
    }

    private val hubBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#131922")
    }

    private val hubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#3B4B5E")
    }

    private val hubBadgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#E6EDF3")
    }

    private val hubCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0B0C10")
    }

    private val hubLogoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.05f
        isFakeBoldText = true
    }

    private val buttonBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#222C3A")
    }

    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#45A29E")
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val dpadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#222C3A")
    }

    private val dpadBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#3B4B5E")
    }

    private val spokePathLeft = Path()
    private val spokePathRight = Path()
    private val spokePathBottom = Path()
    private val paddlePathLeft = Path()
    private val paddlePathRight = Path()
    private val dpadPath = Path()
    private val markerRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val minDim = min(w, h).toFloat()
        outerRadius = (minDim / 2f) * 0.90f
        val rimThickness = outerRadius * 0.23f
        innerRadius = outerRadius - rimThickness
        hubRadius = outerRadius * 0.38f

        rimBasePaint.strokeWidth = rimThickness
        gripContourPaint.strokeWidth = rimThickness * 1.05f

        buildPaths()
    }

    private fun buildPaths() {
        spokePathLeft.reset()
        spokePathRight.reset()
        spokePathBottom.reset()
        paddlePathLeft.reset()
        paddlePathRight.reset()
        dpadPath.reset()

        val spokeHeight = hubRadius * 0.55f

        // Left Spoke
        spokePathLeft.moveTo(-hubRadius * 0.5f, -spokeHeight * 0.5f)
        spokePathLeft.lineTo(-innerRadius * 0.98f, -spokeHeight * 0.9f)
        spokePathLeft.lineTo(-innerRadius * 0.98f, spokeHeight * 0.9f)
        spokePathLeft.lineTo(-hubRadius * 0.5f, spokeHeight * 0.5f)
        spokePathLeft.close()

        // Right Spoke
        spokePathRight.moveTo(hubRadius * 0.5f, -spokeHeight * 0.5f)
        spokePathRight.lineTo(innerRadius * 0.98f, -spokeHeight * 0.9f)
        spokePathRight.lineTo(innerRadius * 0.98f, spokeHeight * 0.9f)
        spokePathRight.lineTo(hubRadius * 0.5f, spokeHeight * 0.5f)
        spokePathRight.close()

        // Bottom Spoke
        val bWidth = hubRadius * 0.65f
        spokePathBottom.moveTo(-bWidth * 0.6f, hubRadius * 0.4f)
        spokePathBottom.lineTo(-bWidth * 0.5f, innerRadius * 0.96f)
        spokePathBottom.lineTo(bWidth * 0.5f, innerRadius * 0.96f)
        spokePathBottom.lineTo(bWidth * 0.6f, hubRadius * 0.4f)
        spokePathBottom.close()

        // Rear Paddle Shifters (behind rim)
        val pW = outerRadius * 0.16f
        val pH = outerRadius * 0.42f
        paddlePathLeft.moveTo(-outerRadius * 0.95f, -pH * 0.5f)
        paddlePathLeft.lineTo(-outerRadius * 1.12f, -pH * 0.2f)
        paddlePathLeft.lineTo(-outerRadius * 1.10f, pH * 0.4f)
        paddlePathLeft.lineTo(-outerRadius * 0.95f, pH * 0.2f)
        paddlePathLeft.close()

        paddlePathRight.moveTo(outerRadius * 0.95f, -pH * 0.5f)
        paddlePathRight.lineTo(outerRadius * 1.12f, -pH * 0.2f)
        paddlePathRight.lineTo(outerRadius * 1.10f, pH * 0.4f)
        paddlePathRight.lineTo(outerRadius * 0.95f, pH * 0.2f)
        paddlePathRight.close()

        // D-Pad Cross on Bottom Spoke
        val dArm = hubRadius * 0.22f
        val dThick = hubRadius * 0.08f
        val dCenterY = hubRadius * 0.92f

        dpadPath.moveTo(-dThick, dCenterY - dArm)
        dpadPath.lineTo(dThick, dCenterY - dArm)
        dpadPath.lineTo(dThick, dCenterY - dThick)
        dpadPath.lineTo(dArm, dCenterY - dThick)
        dpadPath.lineTo(dArm, dCenterY + dThick)
        dpadPath.lineTo(dThick, dCenterY + dThick)
        dpadPath.lineTo(dThick, dCenterY + dArm)
        dpadPath.lineTo(-dThick, dCenterY + dArm)
        dpadPath.lineTo(-dThick, dCenterY + dThick)
        dpadPath.lineTo(-dArm, dCenterY + dThick)
        dpadPath.lineTo(-dArm, dCenterY - dThick)
        dpadPath.lineTo(-dThick, dCenterY - dThick)
        dpadPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (outerRadius <= 0f) return

        canvas.save()
        // Rotate entire wheel according to current steering angle around view center
        canvas.rotate(currentAngle, centerX, centerY)
        canvas.translate(centerX, centerY)

        val rimMidRadius = (outerRadius + innerRadius) / 2f

        // 1. Rear Paddle Shifters (drawn behind wheel rim)
        canvas.drawPath(paddlePathLeft, paddlePaint)
        canvas.drawPath(paddlePathLeft, paddleBorderPaint)
        canvas.drawPath(paddlePathRight, paddlePaint)
        canvas.drawPath(paddlePathRight, paddleBorderPaint)

        // 2. Outer Rim Base Ring (Contoured dark leatherette)
        canvas.drawCircle(0f, 0f, rimMidRadius, rimBasePaint)

        // 3. Ergonomic Grip Swells at 9 o'clock and 3 o'clock (Thicker palm/thumb contours)
        val gripOval = RectF(-rimMidRadius, -rimMidRadius, rimMidRadius, rimMidRadius)
        canvas.drawArc(gripOval, 142f, 76f, false, gripContourPaint)
        canvas.drawArc(gripOval, -38f, 76f, false, gripContourPaint)

        // Subtle grip stitching
        canvas.drawArc(gripOval, 148f, 64f, false, gripStitchPaint)
        canvas.drawArc(gripOval, -32f, 64f, false, gripStitchPaint)

        // 4. Rim Contour Borders (Outer and Inner)
        canvas.drawCircle(0f, 0f, outerRadius, rimOuterBorderPaint)
        canvas.drawCircle(0f, 0f, innerRadius, rimInnerBorderPaint)

        // 5. 12 O'Clock Racing Alignment Stripe (Silver / White metallic centering band)
        val markerW = rimMidRadius * 0.16f
        markerRect.set(-markerW / 2, -outerRadius, markerW / 2, -innerRadius)
        canvas.drawRoundRect(markerRect, 4f, 4f, markerPaint)
        canvas.drawRoundRect(markerRect, 4f, 4f, markerBorderPaint)

        // 6. Molded Horizontal and Vertical Spokes
        canvas.drawPath(spokePathLeft, spokePlatePaint)
        canvas.drawPath(spokePathLeft, spokeBorderPaint)
        canvas.drawPath(spokePathRight, spokePlatePaint)
        canvas.drawPath(spokePathRight, spokeBorderPaint)
        canvas.drawPath(spokePathBottom, spokePlatePaint)
        canvas.drawPath(spokePathBottom, spokeBorderPaint)

        // 7. Left Spoke Buttons (L2, LS, Action Glyphs)
        drawLeftSpokeButtons(canvas)

        // 8. Right Spoke Buttons (R2, RS, Action Glyphs)
        drawRightSpokeButtons(canvas)

        // 9. Bottom Spoke D-Pad & Nav Buttons
        drawBottomSpokeControls(canvas)

        // 10. Center Hub Base and Metallic Bevel
        canvas.drawCircle(0f, 0f, hubRadius, hubBasePaint)
        canvas.drawCircle(0f, 0f, hubRadius, hubBorderPaint)
        canvas.drawCircle(0f, 0f, hubRadius * 0.92f, spokeAccentPaint)

        // 11. Center Circular Emblem Badge with Bold 'N' Logo (Modeled on authentic racing hub)
        val badgeRadius = hubRadius * 0.65f
        canvas.drawCircle(0f, 0f, badgeRadius, hubCenterPaint)
        canvas.drawCircle(0f, 0f, badgeRadius, hubBadgeRingPaint)
        canvas.drawCircle(0f, 0f, badgeRadius * 0.88f, spokeBorderPaint)

        hubLogoPaint.textSize = badgeRadius * 1.15f
        val textY = (hubLogoPaint.descent() + hubLogoPaint.ascent()) / -2f
        canvas.drawText("N", 0f, textY, hubLogoPaint)

        canvas.restore()
    }

    private fun drawLeftSpokeButtons(canvas: Canvas) {
        val spokeMidX = -(hubRadius + innerRadius) * 0.44f
        val spokeMidY = -hubRadius * 0.08f

        // L2 Trigger Button Pill
        val btnW = hubRadius * 0.42f
        val btnH = hubRadius * 0.24f
        val btnRect = RectF(spokeMidX - btnW / 2, spokeMidY - btnH * 1.1f, spokeMidX + btnW / 2, spokeMidY - btnH * 0.1f)
        canvas.drawRoundRect(btnRect, 8f, 8f, buttonBodyPaint)
        canvas.drawRoundRect(btnRect, 8f, 8f, buttonBorderPaint)

        buttonTextPaint.textSize = btnH * 0.65f
        val textY = btnRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("L2", btnRect.centerX(), textY, buttonTextPaint)

        // LS Button
        val roundBtnR = hubRadius * 0.12f
        val roundBtnX = spokeMidX - btnW * 0.1f
        val roundBtnY = spokeMidY + btnH * 0.7f
        canvas.drawCircle(roundBtnX, roundBtnY, roundBtnR, buttonBodyPaint)
        canvas.drawCircle(roundBtnX, roundBtnY, roundBtnR, buttonBorderPaint)
        buttonTextPaint.textSize = roundBtnR * 1.0f
        val lsY = roundBtnY - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("LS", roundBtnX, lsY, buttonTextPaint)
    }

    private fun drawRightSpokeButtons(canvas: Canvas) {
        val spokeMidX = (hubRadius + innerRadius) * 0.44f
        val spokeMidY = -hubRadius * 0.08f

        // R2 Trigger Button Pill
        val btnW = hubRadius * 0.42f
        val btnH = hubRadius * 0.24f
        val btnRect = RectF(spokeMidX - btnW / 2, spokeMidY - btnH * 1.1f, spokeMidX + btnW / 2, spokeMidY - btnH * 0.1f)
        canvas.drawRoundRect(btnRect, 8f, 8f, buttonBodyPaint)
        canvas.drawRoundRect(btnRect, 8f, 8f, buttonBorderPaint)

        buttonTextPaint.textSize = btnH * 0.65f
        val textY = btnRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("R2", btnRect.centerX(), textY, buttonTextPaint)

        // RS Button
        val roundBtnR = hubRadius * 0.12f
        val roundBtnX = spokeMidX + btnW * 0.1f
        val roundBtnY = spokeMidY + btnH * 0.7f
        canvas.drawCircle(roundBtnX, roundBtnY, roundBtnR, buttonBodyPaint)
        canvas.drawCircle(roundBtnX, roundBtnY, roundBtnR, buttonBorderPaint)
        buttonTextPaint.textSize = roundBtnR * 1.0f
        val rsY = roundBtnY - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("RS", roundBtnX, rsY, buttonTextPaint)
    }

    private fun drawBottomSpokeControls(canvas: Canvas) {
        // D-pad Cross
        canvas.drawPath(dpadPath, dpadPaint)
        canvas.drawPath(dpadPath, dpadBorderPaint)

        // Lower Navigation Buttons (Small round dots / icons)
        val dotR = hubRadius * 0.07f
        val baseY = hubRadius * 1.25f
        canvas.drawCircle(-dotR * 2.2f, baseY, dotR, buttonBodyPaint)
        canvas.drawCircle(0f, baseY, dotR, buttonBodyPaint)
        canvas.drawCircle(dotR * 2.2f, baseY, dotR, buttonBodyPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                isSteering = true
                touchStartAngle = calculateAngleFromCenter(event.x, event.y)
                wheelStartAngle = currentAngle
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isSteering) return true
                val currentTouchAngle = calculateAngleFromCenter(event.x, event.y)
                var delta = currentTouchAngle - touchStartAngle

                // Handle angular wrapping across ±180° boundary
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f

                var targetAngle = (wheelStartAngle + delta).coerceIn(-maxSteerAngle, maxSteerAngle)

                // Snap to zero in center deadzone
                if (abs(targetAngle) < deadzoneAngle) {
                    targetAngle = 0f
                }

                currentAngle = targetAngle
                invalidate()
                dispatchAngleIfThrottled(targetAngle)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                isSteering = false
                animateWheelToCenter()
                onReleased?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun calculateAngleFromCenter(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        val rad = atan2(dy.toDouble(), dx.toDouble())
        return (rad * (180.0 / PI)).toFloat()
    }

    private fun dispatchAngleIfThrottled(angle: Float) {
        val now = SystemClock.elapsedRealtime()
        if (abs(angle - lastSentAngle) >= 2f || (now - lastSendTime >= 60L)) {
            lastSentAngle = angle
            lastSendTime = now
            onAngleChanged?.invoke(angle)
        }
    }

    private fun animateWheelToCenter() {
        val start = currentAngle
        val animator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 200L
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { anim ->
                currentAngle = anim.animatedValue as Float
                invalidate()
                onAngleChanged?.invoke(currentAngle)
            }
        }
        animator.start()
    }

    fun resetToCenter() {
        currentAngle = 0f
        invalidate()
        onAngleChanged?.invoke(0f)
    }
}
