package com.nativegamepad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f // 0 to 360
    private var saturation = 1f // 0 to 1
    private var brightness = 1f // 0 to 1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    private val hueColors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    private var hueRect = RectF()
    private var spectrumRect = RectF()

    private var hueGradient: Shader? = null
    private var spectrumBaseGradient: Shader? = null
    private var spectrumOverlayGradient: Shader? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // For shadow
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        updateGradients()
        invalidate()
    }

    fun getColor(): Int {
        return Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 20f
        
        // Hue slider at the bottom
        val hueHeight = 40f
        hueRect.set(padding, h - hueHeight - padding, w - padding, h - padding)
        
        // Spectrum above the hue slider
        spectrumRect.set(padding, padding, w - padding, hueRect.top - 30f)

        hueGradient = LinearGradient(
            hueRect.left, hueRect.top, hueRect.right, hueRect.top,
            hueColors, null, Shader.TileMode.CLAMP
        )

        updateGradients()
    }

    private fun updateGradients() {
        if (spectrumRect.width() <= 0) return

        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        
        // White to Hue Color (Horizontal)
        spectrumBaseGradient = LinearGradient(
            spectrumRect.left, spectrumRect.top, spectrumRect.right, spectrumRect.top,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )

        // Transparent to Black (Vertical)
        spectrumOverlayGradient = LinearGradient(
            spectrumRect.left, spectrumRect.top, spectrumRect.left, spectrumRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Spectrum Base
        paint.shader = spectrumBaseGradient
        canvas.drawRoundRect(spectrumRect, 12f, 12f, paint)

        // Draw Spectrum Overlay
        paint.shader = spectrumOverlayGradient
        canvas.drawRoundRect(spectrumRect, 12f, 12f, paint)

        // Draw Hue Slider
        paint.shader = hueGradient
        canvas.drawRoundRect(hueRect, 20f, 20f, paint)
        paint.shader = null

        // Draw Spectrum Cursor
        val cx = spectrumRect.left + saturation * spectrumRect.width()
        val cy = spectrumRect.top + (1f - brightness) * spectrumRect.height()
        
        paint.color = getColor()
        canvas.drawCircle(cx, cy, 16f, paint)
        canvas.drawCircle(cx, cy, 16f, strokePaint)

        // Draw Hue Cursor
        // Hue gradient colors are from Red -> Magenta -> Blue -> Cyan -> Green -> Yellow -> Red
        // But Color.colorToHSV maps Red=0/360, Yellow=60, Green=120, Cyan=180, Blue=240, Magenta=300
        // Our hueColors array is Red(0), Magenta(60), Blue(120), Cyan(180), Green(240), Yellow(300), Red(360)
        // Wait, HSV Hue is counter-clockwise?
        // Let's use standard HSV mapping where 0=Red, 60=Yellow, 120=Green...
        // Let's fix the hueColors array order to match HSV mapping: Red, Yellow, Green, Cyan, Blue, Magenta, Red
        
        val hueRatio = hue / 360f
        val hx = hueRect.left + hueRatio * hueRect.width()
        val hy = hueRect.centerY()
        
        paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(hx, hy, hueRect.height() / 2f + 4f, paint)
        canvas.drawCircle(hx, hy, hueRect.height() / 2f + 4f, strokePaint)
    }

    private var activeTarget = 0 // 0 = none, 1 = spectrum, 2 = hue

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (spectrumRect.contains(x, y)) {
                    activeTarget = 1
                    handleSpectrumTouch(x, y)
                } else if (hueRect.contains(x, y) || (y > spectrumRect.bottom && y < height)) {
                    activeTarget = 2
                    handleHueTouch(x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeTarget == 1) {
                    handleSpectrumTouch(x, y)
                } else if (activeTarget == 2) {
                    handleHueTouch(x)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTarget = 0
            }
        }
        return true
    }

    private fun handleSpectrumTouch(x: Float, y: Float) {
        var clampedX = x.coerceIn(spectrumRect.left, spectrumRect.right)
        var clampedY = y.coerceIn(spectrumRect.top, spectrumRect.bottom)

        saturation = (clampedX - spectrumRect.left) / spectrumRect.width()
        brightness = 1f - ((clampedY - spectrumRect.top) / spectrumRect.height())
        
        invalidate()
        onColorChanged?.invoke(getColor())
    }

    private fun handleHueTouch(x: Float) {
        var clampedX = x.coerceIn(hueRect.left, hueRect.right)
        hue = ((clampedX - hueRect.left) / hueRect.width()) * 360f
        
        updateGradients()
        invalidate()
        onColorChanged?.invoke(getColor())
    }
}
