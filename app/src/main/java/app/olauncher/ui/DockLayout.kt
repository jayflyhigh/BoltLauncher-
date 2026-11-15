package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import app.olauncher.R

/** Keeps the dock's measured geometry stable while the search viewport expands. */
class DockLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private var searchProgress: Float? = null

    fun setSearchProgress(progress: Float?) {
        searchProgress = progress
        if (isLaidOut) layoutSearchViewport()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutSearchViewport()
    }

    private fun layoutSearchViewport() {
        val progress = searchProgress ?: return
        val search = findViewById<View>(R.id.search) ?: return
        val params = search.layoutParams as LayoutParams
        // Match the original integer margin interpolation, including at fractional densities.
        val endMargin = ((64f - 60f * progress) * resources.displayMetrics.density).toInt()
        val viewportWidth = (width - paddingLeft - paddingRight - params.leftMargin - endMargin)
            .coerceAtLeast(0)
        if (search.width == viewportWidth && search.measuredWidth == viewportWidth) return
        // Only the search subtree needs new dimensions. Do not request a dock/ancestor layout.
        // Measuring the actual input preserves cursor scrolling, hit testing and rounded edges;
        // a clip on a full-width input would change those behaviors for long queries.
        search.measure(
            MeasureSpec.makeMeasureSpec(viewportWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(search.height, MeasureSpec.EXACTLY)
        )
        search.layout(search.left, search.top, search.left + viewportWidth, search.bottom)
    }
}
