package app.olauncher.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class SearchColorWheel(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = floatArrayOf(45f, 1f, 1f)
    var onColor: (Int) -> Unit = {}
    init { contentDescription = "Color wheel. Drag to choose hue and saturation"; isFocusable = true }
    fun setColor(color: Int) { Color.colorToHSV(color, hsv); invalidate() }
    fun setBrightness(value: Float) { hsv[2] = value; invalidate(); onColor(Color.HSVToColor(hsv)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val radius = min(cx, cy) - 14f
        paint.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = RadialGradient(cx, cy, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.color = Color.argb(((1 - hsv[2]) * 255).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, radius, paint)
        val angle = Math.toRadians(hsv[0].toDouble())
        val x = cx + cos(angle).toFloat() * hsv[1] * radius
        val y = cy + sin(angle).toFloat() * hsv[1] * radius
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.BLACK
        canvas.drawCircle(x, y, 10f, paint)
        paint.strokeWidth = 2f; paint.color = Color.WHITE
        canvas.drawCircle(x, y, 8f, paint); paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) return false
        parent.requestDisallowInterceptTouchEvent(true)
        val dx = event.x - width / 2f; val dy = event.y - height / 2f
        hsv[0] = ((atan2(dy, dx) * 180 / PI).toFloat() + 360) % 360
        hsv[1] = (hypot(dx, dy) / (min(width, height) / 2f - 14f)).coerceIn(0f, 1f)
        invalidate(); onColor(Color.HSVToColor(hsv))
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
