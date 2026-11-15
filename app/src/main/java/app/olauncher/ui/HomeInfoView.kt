package app.olauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import java.util.Date

/** A fixed-size dock crown: taps only swap cached text, without animators or delayed clicks. */
class HomeInfoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var mode = 0
    private var battery = "—%"
    private var time = ""
    private var date = ""
    private val label = AppCompatTextView(context)
    private val dots = Array(3) { View(context) }
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
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(6), dp(28), dp(5))
        label.apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            includeFontPadding = false
            setSingleLine()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val indicators = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dots.forEach { dot ->
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            indicators.addView(dot, LayoutParams(dp(6), dp(6)).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
        addView(indicators, LayoutParams(LayoutParams.WRAP_CONTENT, dp(12)))
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
        label.text = when (mode) { 1 -> battery; 2 -> date; else -> time }
        dots.forEachIndexed { index, dot ->
            (dot.background as GradientDrawable).setColor(
                if (index == mode) Color.rgb(255, 214, 0) else Color.rgb(128, 128, 128)
            )
        }
        contentDescription = when (mode) {
            1 -> "Battery $battery. Tap to show date"
            2 -> "$date. Tap to show time"
            else -> "$time. Tap to show battery"
        }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()

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
