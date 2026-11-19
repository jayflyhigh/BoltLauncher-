package app.olauncher.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
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
            setPadding((20 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(Color.rgb(175, 168, 191))
            textSize = 12f
            letterSpacing = 0.06f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.label.text = title }
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
