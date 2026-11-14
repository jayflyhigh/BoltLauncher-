package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/** Keep the upper cutoff, with only a small fade immediately above the dock. */
class DockResultsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    override fun getBottomFadingEdgeStrength(): Float =
        super.getBottomFadingEdgeStrength() * 0.2f
}
