package app.olauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
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
        setBackgroundResource(app.olauncher.R.drawable.home_info_crown)
        gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        textSize = 20f
        setPadding(dp(28), dp(6), dp(28), dp(18))
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
        invalidate()
        contentDescription = when (mode) {
            1 -> "Battery $battery. Tap to show date"
            2 -> "$date. Tap to show time"
            else -> "$time. Tap to show battery"
        }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height - 8f * density
        for (index in 0..2) {
            dotPaint.color = if (index == mode) Color.rgb(255, 214, 0) else Color.rgb(128, 128, 128)
            canvas.drawCircle(centerX + (index - 1) * 10f * density, centerY, 2.5f * density, dotPaint)
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

}
