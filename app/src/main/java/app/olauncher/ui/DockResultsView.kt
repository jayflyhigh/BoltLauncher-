package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Reveal rows as they enter the results viewport during filtering or scrolling. */
class DockResultsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private var revealRows = false

    fun updateRowMotion(searchVisible: Boolean) {
        revealRows = searchVisible && LauncherMotion.isEnabled(context)
        if (!revealRows) {
            for (index in 0 until childCount) resetRow(getChildAt(index))
        }
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        if (revealRows && isShown) {
            LauncherMotion.revealAttached(child, fromAlpha = 0.65f, riseDp = 7f)
        }
    }

    override fun onChildDetachedFromWindow(child: View) {
        resetRow(child)
        super.onChildDetachedFromWindow(child)
    }

    override fun onDetachedFromWindow() {
        updateRowMotion(searchVisible = false)
        super.onDetachedFromWindow()
    }

    private fun resetRow(child: View) {
        if (child.alpha != 1f || child.translationY != 0f) LauncherMotion.reset(child)
    }
}
