package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled

/** Keep the upper cutoff, with only a small fade immediately above the dock. */
class DockResultsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private var animationGeneration = 0
    private var lastQuery: String? = null

    /**
     * Filtering must not animate or reset existing rows. DiffUtil already updates the
     * contents; moving/dimming every visible row made rapid typing visibly blink.
     */
    fun animateSearchResults(query: String, force: Boolean = false) {
        lastQuery = query
        if (force) {
            animationGeneration++
            resetRows()
        }
    }

    /**
     * On a mode change, preserve the current row positions. A subtle opacity settle
     * is enough, with no Y translation, no stagger and no repeated blank frames.
     * The keyboard and dock are never animated by the results view.
     */
    fun animateModeChange() {
        val generation = ++animationGeneration
        resetRows()
        if (!isShown || context.isEinkDisplay() || context.isSystemAnimationsDisabled()) return
        doOnPreDraw {
            if (generation != animationGeneration || !isShown ||
                scrollState != SCROLL_STATE_IDLE) return@doOnPreDraw
            for (index in 0 until childCount) {
                val row = getChildAt(index)
                row.animate().cancel()
                row.alpha = 0.92f
                row.translationY = 0f
                row.animate().alpha(1f)
                    .setDuration(130L)
                    .setStartDelay(0L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f))
                    .start()
            }
        }
    }

    override fun onScrollStateChanged(state: Int) {
        if (state != SCROLL_STATE_IDLE) {
            animationGeneration++
            resetRows()
        }
        super.onScrollStateChanged(state)
    }

    private fun resetRows() {
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                animate().cancel()
                alpha = 1f
                translationY = 0f
            }
        }
    }

    override fun onChildDetachedFromWindow(child: android.view.View) {
        child.animate().cancel()
        child.alpha = 1f
        child.translationY = 0f
        super.onChildDetachedFromWindow(child)
    }

    override fun onDetachedFromWindow() {
        animationGeneration++
        resetRows()
        super.onDetachedFromWindow()
    }

    override fun getBottomFadingEdgeStrength(): Float =
        super.getBottomFadingEdgeStrength() * 0.2f
}
