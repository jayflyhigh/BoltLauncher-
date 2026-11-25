package app.olauncher.ui

import android.content.Context
import android.graphics.Path
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import app.olauncher.data.Prefs.LauncherTheme
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled

/** Short, interruptible transitions for launcher state changes. */
internal object LauncherMotion {
    const val MODE_DURATION = 180L
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

    fun revealForTheme(view: View, theme: LauncherTheme) {
        if (theme == LauncherTheme.DEFAULT || !view.isShown || !isEnabled(view.context)) return
        val density = view.resources.displayMetrics.density
        val end = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val center = Animation.RELATIVE_TO_SELF
        fun scale(x: Float, y: Float, pivotX: Float = 0.5f, pivotY: Float = 0.5f) =
            ScaleAnimation(x, 1f, y, 1f, center, pivotX, center, pivotY)
        fun slide(x: Float, y: Float) = TranslateAnimation(x * density, 0f, y * density, 0f)
        fun fade(alpha: Float) = AlphaAnimation(alpha, 1f)

        // Draw-only transforms leave focus, layout, and hit targets ready immediately.
        val reveal = AnimationSet(false).apply {
            fun play(animation: Animation, time: Long, curve: Interpolator = settle) {
                animation.duration = time
                animation.interpolator = curve
                addAnimation(animation)
            }

            when (theme) {
                LauncherTheme.DEFAULT -> Unit
                LauncherTheme.WALLPAPER -> {
                    play(fade(0.08f), 320L, LinearInterpolator())
                    play(scale(1.08f, 1.08f), 320L, DecelerateInterpolator())
                }
                LauncherTheme.FROSTED_GLASS -> {
                    play(fade(0.28f), 285L, DecelerateInterpolator())
                    play(scale(0.76f, 1f), 285L)
                }
                LauncherTheme.LIQUID_GLASS -> {
                    play(scale(1.10f, 0.65f), 340L, OvershootInterpolator(1.8f))
                }
                LauncherTheme.COZY_QUEST -> {
                    play(
                        RotateAnimation(-5f * end, 0f, center, 0.5f, center, 1f),
                        330L,
                        OvershootInterpolator(1.35f),
                    )
                    play(slide(0f, 22f), 330L, OvershootInterpolator(1.35f))
                }
                LauncherTheme.E_INK -> {
                    // A quick two-tone refresh with no spatial movement.
                    play(fade(0.40f), 145L, PathInterpolator(
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(0.20f, 0.12f)
                            lineTo(0.21f, 0.72f)
                            lineTo(0.70f, 0.72f)
                            lineTo(0.71f, 1f)
                            lineTo(1f, 1f)
                        },
                    ))
                }
                LauncherTheme.PAPER_NOTEBOOK -> {
                    val spine = if (end > 0) 0f else 1f
                    play(scale(0.32f, 1f, pivotX = spine), 290L, DecelerateInterpolator(1.65f))
                }
                LauncherTheme.SMOKED_GLASS -> {
                    play(slide(0f, -28f), 320L, DecelerateInterpolator(1.25f))
                    play(fade(0.15f), 320L, AccelerateInterpolator())
                }
                LauncherTheme.WINDOWS_XP -> {
                    play(scale(1f, 0.45f, pivotY = 1f), 220L, DecelerateInterpolator())
                    play(slide(0f, 24f), 220L, DecelerateInterpolator())
                }
                LauncherTheme.WII_U -> {
                    play(scale(0.75f, 0.75f), 310L, OvershootInterpolator(2.4f))
                    play(fade(0.65f), 140L)
                }
                LauncherTheme.SEATTLE_SEAHAWKS -> {
                    play(slide(-44f * end, 0f), 235L, OvershootInterpolator(1.1f))
                }
            }
        }
        view.startAnimation(reveal)
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
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(settle)
            .start()
    }

    fun setRowHighlight(view: View, selected: Boolean, animate: Boolean) {
        val changed = view.isSelected != selected
        val background = view.background as? StateListDrawable
        val fade = if (changed && animate && isEnabled(view.context)) 110 else 0
        if (changed || !animate) {
            background?.setEnterFadeDuration(fade)
            background?.setExitFadeDuration(fade)
            view.isSelected = selected
        }
        if (!animate) background?.jumpToCurrentState()
    }

    fun configureResultPress(view: View) {
        val foreground = view.foreground as? StateListDrawable ?: return
        // Keep touch-down immediate and let the press fade over the opening actions on release.
        foreground.setExitFadeDuration(if (isEnabled(view.context)) 100 else 0)
    }

    fun reset(view: View) {
        view.clearAnimation()
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.translationX = 0f
    }
}
