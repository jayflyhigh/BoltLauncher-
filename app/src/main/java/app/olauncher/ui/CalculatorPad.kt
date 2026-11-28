package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.helper.SearchCalculator
import app.olauncher.helper.getColorFromAttr
import kotlin.math.roundToInt

class CalculatorPad(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    private val expressionLabel = TextView(context)
    private val answerLabel = TextView(context)
    private var expression = ""
    private var answer: String? = null
    private var submitted = false

    private fun createKeys() {
        layoutDirection = LAYOUT_DIRECTION_LTR
        overScrollMode = OVER_SCROLL_NEVER
        val colors = WallpaperThemeColors.from(context)
        val inset = resources.getDimensionPixelSize(R.dimen.bolt_search_results_inset)
        val gap = resources.getDimensionPixelSize(R.dimen.bolt_result_dock_gap)
        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, dp(8), inset, gap)
        }
        addView(keys)
        val displayColor = context.getColorFromAttr(R.attr.boltResultTextColor)
        listOf(expressionLabel, answerLabel).forEach { label ->
            label.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            label.textDirection = TEXT_DIRECTION_LTR
            label.textSize = 20f
            label.setTextColor(displayColor)
            label.setPadding(dp(8), dp(2), dp(8), dp(2))
            label.maxLines = 2
            keys.addView(label)
        }
        answerLabel.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        val rows = listOf(
            listOf("AC", "( )", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "⌫", "="),
        )
        for (labels in rows) {
            val row = LinearLayout(context)
            keys.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            for (label in labels) {
                val button = AppCompatButton(context).apply {
                    text = label
                    contentDescription = when (label) {
                        "AC" -> context.getString(R.string.bolt_calculator_clear)
                        "⌫" -> context.getString(R.string.bolt_calculator_delete)
                        "( )" -> context.getString(R.string.bolt_calculator_parentheses)
                        "%" -> context.getString(R.string.bolt_calculator_percent)
                        else -> null
                    }
                    textSize = 18f
                    setPadding(0, 0, 0, 0)
                    supportBackgroundTintList = null
                    val selected = colors?.rowBackground(context)
                        ?: ContextCompat.getDrawable(context, R.drawable.bolt_search_best_match)
                    background = selected?.apply { setState(intArrayOf(android.R.attr.state_selected)) }?.current?.mutate()
                    foreground = ContextCompat.getDrawable(context, R.drawable.bolt_result_press)
                    setTextColor(if (colors != null) Color.BLACK else context.getColorFromAttr(R.attr.boltSelectedRowTextColor))
                    setOnClickListener { press(label) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
    }

    fun reset() {
        if (childCount == 0) createKeys()
        expression = ""
        submitted = false
        refresh()
        scrollTo(0, 0)
    }

    private fun press(key: String) {
        when (key) {
            "AC" -> {
                reset()
                return
            }
            "⌫" -> {
                submitted = false
                expression = expression.dropLast(1)
            }
            "=" -> answer?.let {
                expression = it
                submitted = true
            }
            "( )" -> addParenthesis()
            "%" -> if (expression.lastOrNull()?.let { it.isDigit() || it == '.' || it == ')' } == true &&
                expression.length < 160) {
                expression += "%"
                submitted = false
            }
            else -> {
                if (submitted) {
                    if (!isOperator(key.single())) expression = ""
                    submitted = false
                }
                if (expression.endsWith('%') && !isOperator(key.single())) return
                if (expression.length < 160) expression += key
            }
        }
        refresh()
    }

    private fun addParenthesis() {
        if (submitted) {
            expression = ""
            submitted = false
        }
        val afterOperand = expression.lastOrNull()?.let {
            it.isDigit() || it == '.' || it == ')' || it == '%'
        } == true
        val hasOpenGroup = expression.count { it == '(' } > expression.count { it == ')' }
        val addition = when {
            !afterOperand -> "("
            hasOpenGroup -> ")"
            else -> "×("
        }
        if (expression.length + addition.length <= 160) expression += addition
    }

    private fun isOperator(key: Char): Boolean = key in "+−-×÷"

    private fun refresh() {
        answer = if (submitted) null else SearchCalculator.evaluate(expression)
        expressionLabel.text = expression.ifEmpty { "0" }
        answerLabel.text = answer?.let { context.getString(R.string.bolt_calculator_result, it) }.orEmpty()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
