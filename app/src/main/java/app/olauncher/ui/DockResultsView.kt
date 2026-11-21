package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R

/** Reveal each result once per search session, even if filtering reattaches its view. */
class DockResultsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private var revealRows = false
    private val seenRows = mutableSetOf<Any>()

    fun updateRowMotion(searchVisible: Boolean) {
        revealRows = searchVisible && LauncherMotion.isEnabled(context)
        if (!revealRows) {
            seenRows.clear()
            for (index in 0 until childCount) resetRow(getChildAt(index))
        } else {
            for (index in 0 until childCount) {
                getChildAt(index).getTag(R.id.bolt_result_identity)?.let(seenRows::add)
            }
        }
    }

    fun rememberRow(child: View, identity: Any) {
        if (revealRows && isShown && child.isAttachedToWindow && seenRows.add(identity)) {
            resetRow(child)
            LauncherMotion.revealAttached(child, fromAlpha = 0.78f, riseDp = 0f)
        }
    }

    // Show touch-down feedback while RecyclerView decides whether the gesture is a scroll.
    override fun shouldDelayChildPressedState() = false

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        child.getTag(R.id.bolt_result_identity)?.let { rememberRow(child, it) }
    }

    override fun onChildDetachedFromWindow(child: View) {
        child.isPressed = false
        child.foreground?.jumpToCurrentState()
        resetRow(child)
        super.onChildDetachedFromWindow(child)
    }

    override fun onDetachedFromWindow() {
        updateRowMotion(searchVisible = false)
        super.onDetachedFromWindow()
    }

    private fun resetRow(child: View) {
        LauncherMotion.reset(child)
    }
}
