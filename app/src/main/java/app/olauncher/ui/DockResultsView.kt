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

    /** Animate the rows, keeping the panel and keyboard completely stationary. */
    fun animateSearchResults(query: String, force: Boolean = false) {
        // Filtering should stay steady: never restart every visible row on a keystroke.
        if (!force) {
            if (lastQuery != query) {
                animationGeneration++
                resetRows()
            }
            lastQuery = query
            return
        }
        lastQuery = query
        val generation = ++animationGeneration
        resetRows()
        if (scrollState != SCROLL_STATE_IDLE || !isShown || context.isEinkDisplay() || context.isSystemAnimationsDisabled()) return
        doOnPreDraw {
            if (generation != animationGeneration || scrollState != SCROLL_STATE_IDLE || !isShown) return@doOnPreDraw
            val rows = (0 until childCount).map { getChildAt(it) }.sortedByDescending { it.top }
            rows.forEachIndexed { index, row ->
                row.animate().cancel()
                row.alpha = 0.72f
                row.translationY = 8f * resources.displayMetrics.density
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay((index.coerceAtMost(5) * 8).toLong())
                    .setDuration(300L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f))
                    .start()
            }
        }
    }

    /** Crossfade and gently settle newly bound rows without moving the panel or IME.
     * Repeated mode taps cancel the previous pass instead of stacking animations.
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
                row.alpha = 0.65f
                row.translationY = 5f * resources.displayMetrics.density
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(190L)
                    .setStartDelay((index.coerceAtMost(4) * 12).toLong())
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
