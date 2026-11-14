package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import app.olauncher.data.AppModel
import app.olauncher.helper.InAppSearch

object AppSearchEditor {
    fun show(context: Context, apps: List<AppModel.App>, search: InAppSearch,
             initial: InAppSearch.Target?, onChanged: () -> Unit): AlertDialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val nested = mutableListOf<AlertDialog>()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        fun label(text: String) { panel.addView(TextView(context).apply { this.text = text; setPadding(0, dp(14), 0, dp(6)) }) }
        var chosen: AppModel.App? = initial?.app
        var color = initial?.color ?: Color.rgb(255, 213, 79)
        label("1. Choose an app")
        val appButton = Button(context).apply { isAllCaps = false; text = chosen?.appLabel ?: "Choose app" }
        panel.addView(appButton)
        label("2. Search URL")
        val url = EditText(context).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/search?q={query}"
            setText(initial?.template.orEmpty())
        }
        panel.addView(url)
        panel.addView(TextView(context).apply {
            text = "Use {query} or %s where the search text goes. If neither is present, the search text is appended to the URL."
            textSize = 12f
        })
        label("3. Alias code")
        val alias = EditText(context).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "Your alias code"
            setText(initial?.code.orEmpty())
        }
        panel.addView(alias)
        label("4. Code background color")
        val colorButton = Button(context).apply { isAllCaps = false }
        fun refreshColor() {
            colorButton.text = "Color wheel / ${String.format("#%06X", color and 0xFFFFFF)}"
            colorButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            colorButton.setTextColor(InAppSearch.foreground(color))
        }
        refreshColor(); panel.addView(colorButton)
        val error = TextView(context).apply { setTextColor(Color.rgb(255, 110, 100)); visibility = View.GONE }
        panel.addView(error)
        appButton.setOnClickListener {
            if (apps.isEmpty()) { error.text = "No apps available"; error.visibility = View.VISIBLE; return@setOnClickListener }
            val picker = AlertDialog.Builder(context).setTitle("Choose app")
                .setItems(apps.map { it.appLabel }.toTypedArray()) { _, index ->
                    chosen = apps[index]; appButton.text = chosen!!.appLabel
                    val saved = search.targets[chosen!!.appPackage]
                    url.setText(saved?.template.orEmpty()); alias.setText(saved?.code.orEmpty())
                    color = saved?.color ?: Color.rgb(255, 213, 79); refreshColor()
                    error.visibility = View.GONE
                }.setNegativeButton(android.R.string.cancel, null).create()
            nested.add(picker); picker.show()
        }
        colorButton.setOnClickListener {
            nested.add(showColor(context, color, alias.text.toString().trim()) { color = it; refreshColor() })
        }
        val remove = Button(context).apply { text = "Remove command for selected app"; isAllCaps = false }
        panel.addView(remove)
        val dialog = AlertDialog.Builder(context).setTitle("Search within Apps")
            .setView(ScrollView(context).apply { addView(panel) })
            .setPositiveButton("Apply", null).setNegativeButton(android.R.string.cancel, null).create()
        remove.setOnClickListener {
            val app = chosen
            if (app == null || search.targets[app.appPackage] == null) {
                error.text = "Choose an app with a saved command"; error.visibility = View.VISIBLE
            } else { search.remove(app.appPackage); onChanged(); dialog.dismiss() }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val app = chosen
                if (app == null) { error.text = "Choose an app first"; error.visibility = View.VISIBLE; return@setOnClickListener }
                try {
                    search.save(app, url.text.toString(), color, alias.text.toString())
                    onChanged(); dialog.dismiss()
                } catch (e: IllegalArgumentException) {
                    error.text = e.message; error.visibility = View.VISIBLE
                }
            }
        }
        dialog.setOnDismissListener { nested.forEach { it.dismiss() } }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        return dialog
    }

    private fun showColor(context: Context, initial: Int, code: String, apply: (Int) -> Unit): AlertDialog {
        val density = context.resources.displayMetrics.density
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding((20*density).toInt(), 0, (20*density).toInt(), 0) }
        val preview = androidx.appcompat.widget.AppCompatTextView(context).apply {
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
            preview.background = InAppSearch.background(color, density)
            preview.setTextColor(InAppSearch.foreground(color))
        }
        val wheel = SearchColorWheel(context)
        panel.addView(wheel, LinearLayout.LayoutParams(-1, (220*density).toInt()))
        panel.addView(TextView(context).apply { text = "Brightness" })
        val brightness = SeekBar(context).apply { max = 100 }
        panel.addView(brightness)
        val hex = EditText(context).apply { setSingleLine(true); hint = "Hex color, e.g. #FF5722" }
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
        val dialog = AlertDialog.Builder(context).setTitle("Code background color").setView(ScrollView(context).apply { addView(panel) })
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
