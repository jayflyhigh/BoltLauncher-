package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.InAppSearch
import app.olauncher.helper.AppIconColor

object AppSearchEditor {
    fun show(context: Context, search: InAppSearch, initial: InAppSearch.Target?,
             app: AppModel.App? = null, onChanged: () -> Unit): AlertDialog {
        val packageName = initial?.packageName ?: requireNotNull(app).appPackage
        val appLabel = initial?.appLabel ?: requireNotNull(app).appLabel
        val builder = AlertDialog.Builder(context)
        val dialogContext = builder.context
        val density = dialogContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        fun label(text: String) {
            panel.addView(TextView(dialogContext).apply { this.text = text; setPadding(0, dp(14), 0, dp(6)) })
        }
        label("Alias code")
        val alias = EditText(dialogContext).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(initial?.code.orEmpty())
        }
        panel.addView(alias)
        var color = initial?.color ?: AppIconColor.primary(context, app)
        label("Badge color")
        val colorButton = Button(dialogContext).apply { isAllCaps = false }
        fun refreshColor() {
            colorButton.text = String.format("#%06X", color and 0xFFFFFF)
            colorButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            colorButton.setTextColor(InAppSearch.foreground(color))
        }
        refreshColor()
        panel.addView(colorButton)
        var colorDialog: AlertDialog? = null
        colorButton.setOnClickListener {
            colorDialog = showColor(context, color, alias.text.toString()) { color = it; refreshColor() }
        }
        val url = EditText(dialogContext).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/search?q={query}"
            setText(initial?.template.orEmpty())
        }
        if (initial?.builtIn != true) {
            label("Search URL")
            panel.addView(url)
        }
        val error = TextView(dialogContext).apply {
            val backgroundColor = dialogContext.getColorFromAttr(R.attr.boltDialogTopColor)
            setTextColor(if (InAppSearch.foreground(backgroundColor) == Color.BLACK)
                Color.rgb(140, 25, 22) else Color.rgb(255, 186, 181))
            visibility = View.GONE
        }
        panel.addView(error)
        builder.setTitle(appLabel)
            .setView(ScrollView(dialogContext).apply { addView(panel) })
            .setPositiveButton(if (initial?.hidden == true) "Show" else "Save", null)
            .setNegativeButton(android.R.string.cancel, null)
        if (initial != null && !initial.hidden) {
            builder.setNeutralButton(if (initial.builtIn) "Hide" else "Delete") { _, _ ->
                search.remove(initial)
                onChanged()
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    search.save(packageName, appLabel, url.text.toString(), color, alias.text.toString())
                    onChanged()
                    dialog.dismiss()
                } catch (e: IllegalArgumentException) {
                    error.text = e.message
                    error.visibility = View.VISIBLE
                }
            }
        }
        dialog.setOnDismissListener { colorDialog?.dismiss() }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        return dialog
    }

    private fun showColor(context: Context, initial: Int, code: String, apply: (Int) -> Unit): AlertDialog {
        val builder = AlertDialog.Builder(context)
        val dialogContext = builder.context
        val density = dialogContext.resources.displayMetrics.density
        val panel = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL; setPadding((20*density).toInt(), 0, (20*density).toInt(), 0) }
        val preview = androidx.appcompat.widget.AppCompatTextView(dialogContext).apply {
            text = code.ifBlank { "Alias" }
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((3*density).toInt(), 0, (3*density).toInt(), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Preview of alias icon: ${code.ifBlank { "Alias" }}"
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        panel.addView(preview, LinearLayout.LayoutParams((48*density).toInt(), (48*density).toInt()).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = (8*density).toInt()
            bottomMargin = (8*density).toInt()
        })
        fun updatePreview(color: Int) {
            preview.styleAliasBadge(color)
        }
        val wheel = SearchColorWheel(dialogContext)
        panel.addView(wheel, LinearLayout.LayoutParams(-1, (220*density).toInt()))
        panel.addView(TextView(dialogContext).apply { text = "Brightness" })
        val brightness = SeekBar(dialogContext).apply { max = 100 }
        panel.addView(brightness)
        val hex = EditText(dialogContext).apply { setSingleLine(true); hint = "Hex color, e.g. #FF5722" }
        panel.addView(hex)
        var selected = initial
        var updating = false
        fun display(color: Int, syncWheel: Boolean = true) {
            updating = true; selected = color
            updatePreview(color)
            hex.setText(String.format("#%06X", color and 0xFFFFFF)); hex.error = null
            if (syncWheel) wheel.setColor(color)
            val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
            brightness.progress = (hsv[2]*100).toInt(); updating = false
        }
        wheel.onColor = { color -> if (!updating) display(color, false) }
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updating) wheel.setBrightness(progress / 100f)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updating) return
                val value = s.toString().trim().removePrefix("#")
                if (value.matches(Regex("[0-9a-fA-F]{6}"))) {
                    selected = Color.parseColor("#$value"); updatePreview(selected); updating = true
                    wheel.setColor(selected); val hsv = FloatArray(3); Color.colorToHSV(selected, hsv)
                    brightness.progress = (hsv[2]*100).toInt(); updating = false; hex.error = null
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        display(initial)
        val dialog = builder.setTitle("Code background color").setView(ScrollView(dialogContext).apply { addView(panel) })
            .setPositiveButton("Use color", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!hex.text.toString().trim().removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))) {
                    hex.error = "Enter a six-digit hex color"
                } else { apply(selected); dialog.dismiss() }
            }
        }
        dialog.show(); return dialog
    }
}
