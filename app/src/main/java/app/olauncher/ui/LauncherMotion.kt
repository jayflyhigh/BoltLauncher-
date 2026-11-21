package app.olauncher.ui

import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled

/** Short, interruptible transitions for launcher state changes. */
internal object LauncherMotion {
    const val MODE_DURATION = 180L
    const val SEARCH_DURATION = 220L
    private const val REVEAL_DURATION = 140L
    val settle = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun isEnabled(context: Context): Boolean =
        !context.isEinkDisplay() && !context.isSystemAnimationsDisabled()

    // The caller applies visibility and focus immediately and resets motion when hiding the view.
    fun reveal(
        view: View,
        fromAlpha: Float = 0.75f,
        riseDp: Float = 0f,
        fromScale: Float = 1f,
        duration: Long = REVEAL_DURATION,
    ) {
        reset(view)
        if (!view.isShown || !isEnabled(view.context)) return
        revealAttached(view, fromAlpha, riseDp, fromScale, duration)
    }

    // Results check the motion preference when search opens, outside the row attachment path.
    fun revealAttached(
        view: View,
        fromAlpha: Float,
        riseDp: Float,
        fromScale: Float = 1f,
        duration: Long = REVEAL_DURATION,
    ) {
        view.alpha = fromAlpha
        view.translationY = riseDp * view.resources.displayMetrics.density
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(settle)
            .start()
    }

    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }
}
