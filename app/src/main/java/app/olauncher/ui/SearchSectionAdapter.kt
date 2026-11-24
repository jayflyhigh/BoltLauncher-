package app.olauncher.ui

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.getColorFromAttr
import androidx.recyclerview.widget.RecyclerView

/** A non-interactive category label; omitted when its section has no results. */
class SearchSectionAdapter(private val title: String) : RecyclerView.Adapter<SearchSectionAdapter.Holder>() {
    private var visible = false
    fun show(show: Boolean) {
        if (visible == show) return
        visible = show
        if (show) notifyItemInserted(0) else notifyItemRemoved(0)
    }
    override fun getItemCount() = if (visible) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        return Holder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val theme = Prefs(context).launcherTheme
            if (theme == Prefs.LauncherTheme.PAPER_NOTEBOOK) {
                setBackgroundResource(R.drawable.bolt_notebook_section)
            }
            if (theme == Prefs.LauncherTheme.COZY_QUEST) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.bolt_cozy_sprout, 0, 0, 0)
                compoundDrawablePadding = (6 * density).toInt()
            }
            setPadding((20 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(WallpaperThemeColors.from(context)?.accent ?: context.getColorFromAttr(R.attr.boltSearchSectionColor))
            textSize = 13f
            letterSpacing = 0.04f
            typeface = when (theme) {
                Prefs.LauncherTheme.COZY_QUEST -> Typeface.create("casual", Typeface.BOLD)
                Prefs.LauncherTheme.E_INK -> Typeface.create("serif", Typeface.BOLD)
                else -> Typeface.DEFAULT_BOLD
            }
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.label.text = title }
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
