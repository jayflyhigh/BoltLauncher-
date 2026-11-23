package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class SearchColorWheel(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = floatArrayOf(45f, 1f, 1f)
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var hues: Shader? = null
    private var saturation: Shader? = null
    var onColor: (Int) -> Unit = {}

    init {
        contentDescription = "Color wheel. Drag to choose hue and saturation"
        isFocusable = true
    }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    fun setBrightness(value: Float) {
        hsv[2] = value
        invalidate()
        onColor(Color.HSVToColor(hsv))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) - 14f * density
        if (radius <= 0f) return
        hues = SweepGradient(centerX, centerY,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        saturation = RadialGradient(centerX, centerY, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        paint.color = Color.WHITE
        paint.shader = hues
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.shader = saturation
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.shader = null
        paint.color = Color.argb(((1 - hsv[2]) * 255).toInt(), 0, 0, 0)
        canvas.drawCircle(centerX, centerY, radius, paint)
        val angle = Math.toRadians(hsv[0].toDouble())
        val x = centerX + cos(angle).toFloat() * hsv[1] * radius
        val y = centerY + sin(angle).toFloat() * hsv[1] * radius
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        canvas.drawCircle(x, y, 10f * density, paint)
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 8f * density, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (radius <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP -> parent.requestDisallowInterceptTouchEvent(false)
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
            else -> return true
        }
        val dx = event.x - centerX
        val dy = event.y - centerY
        hsv[0] = ((atan2(dy, dx) * 180 / PI).toFloat() + 360) % 360
        hsv[1] = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        invalidate()
        onColor(Color.HSVToColor(hsv))
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
