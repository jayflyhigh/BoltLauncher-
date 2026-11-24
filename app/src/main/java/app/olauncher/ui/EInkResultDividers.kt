package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R

/** Draws a rule in the existing gap between rows in each result section. */
internal class EInkResultDividers(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val inset = 12f * density
    private val gap = context.resources.getDimension(R.dimen.bolt_result_row_margin)
    private val rule = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bolt_eink_rule)
        strokeWidth = density
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val reverse = (parent.layoutManager as LinearLayoutManager).reverseLayout
        for (index in 0 until parent.childCount) {
            val row = parent.getChildAt(index)
            val holder = parent.getChildViewHolder(row)
            val adapter = holder.bindingAdapter ?: continue
            if (adapter is SearchSectionAdapter || holder.bindingAdapterPosition !in 0 until adapter.itemCount - 1) continue
            val y = row.translationY + if (reverse) row.top - gap else row.bottom + gap
            if (y > parent.paddingTop && y < parent.height - parent.paddingBottom) {
                canvas.drawLine(row.left + inset, y, row.right - inset, y, rule)
            }
        }
    }
}
