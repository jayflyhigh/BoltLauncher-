package app.olauncher.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

internal class CalculatorResultAdapter(
    private val wallpaperColors: () -> WallpaperThemeColors?,
) : RecyclerView.Adapter<CalculatorResultAdapter.Holder>() {

    var answer: String? = null
        set(value) {
            if (field == value) return
            val hadAnswer = field != null
            field = value
            when {
                value == null -> notifyItemRemoved(0)
                hadAnswer -> notifyItemChanged(0)
                else -> notifyItemInserted(0)
            }
        }

    override fun getItemCount() = if (answer == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.adapter_calculator_result, parent, false) as TextView
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val view = holder.label
        val colors = wallpaperColors()
        view.isSelected = true
        if (colors == null) view.setBackgroundResource(R.drawable.bolt_search_best_match)
        else view.background = colors.rowBackground(view.context)
        view.setTextColor(if (colors != null) Color.BLACK else view.context.getColorFromAttr(R.attr.boltSelectedRowTextColor))
        view.text = view.context.getString(R.string.bolt_calculator_result, answer.orEmpty())
    }

    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
