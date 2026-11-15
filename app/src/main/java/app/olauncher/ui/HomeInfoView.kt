package app.olauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import java.util.Date

/** A fixed-size dock crown: taps only swap cached text, without animators or delayed clicks. */
class HomeInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private var mode = 0
    private var battery = "—%"
    private var time = ""
    private var date = ""
    private val shell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 39, 41) }
    private val crown = Path()
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "—%"
            }
            refreshLabels()
        }
    }

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 18f
        includeFontPadding = false
        setSingleLine()
        setOnClickListener { cycle() }
        refreshLabels()
    }

    fun cycle() {
        mode = (mode + 1) % 3
        showLabel()
    }

    private fun refreshLabels() {
        val now = Date()
        time = android.text.format.DateFormat.getTimeFormat(context).format(now)
        date = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now)
        showLabel()
    }

    private fun showLabel() {
        text = when (mode) { 1 -> battery; 2 -> date; else -> time }
        contentDescription = when (mode) {
            1 -> "Battery $battery. Tap to show date"
            2 -> "$date. Tap to show time"
            else -> "$time. Tap to show battery"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshLabels()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Only protected system broadcasts; registration also delivers the sticky battery value.
        context.registerReceiver(updates, filter)
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(updates)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val x = w.toFloat()
        val y = h.toFloat()
        crown.reset()
        crown.moveTo(0f, y)
        crown.cubicTo(x * .12f, y, x * .10f, 0f, x * .28f, 0f)
        crown.lineTo(x * .72f, 0f)
        crown.cubicTo(x * .90f, 0f, x * .88f, y, x, y)
        crown.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(crown, shell)
        super.onDraw(canvas)
    }
}
