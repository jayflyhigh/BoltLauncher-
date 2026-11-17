package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

/** Open the selected home search with one upward swipe, leaving child taps intact. */
class SearchGestureLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    var onBackBeforeIme: (() -> Boolean)? = null
    private var consumingBack = false

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                consumingBack = onBackBeforeIme?.invoke() == true
            }
            if (consumingBack) {
                if (event.action == KeyEvent.ACTION_UP) consumingBack = false
                return true
            }
        }
        return super.dispatchKeyEventPreIme(event)
    }

    var canSwipeSearch: (() -> Boolean)? = null
    var onSwipeUp: (() -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var candidate = false
    private var consumed = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                consumed = false
                candidate = canSwipeSearch?.invoke() == true
            }
            MotionEvent.ACTION_POINTER_DOWN -> candidate = false
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (!consumed && candidate && -dy > 64f * resources.displayMetrics.density &&
                    -dy > abs(dx) && canSwipeSearch?.invoke() == true) {
                    consumed = true
                    candidate = false
                    // Cancel the original target so releasing a swipe cannot click or launch it.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    onSwipeUp?.invoke()
                }
            }
        }
        if (consumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumed = false
                candidate = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            candidate = false
        }
        val handled = super.dispatchTouchEvent(event)
        // Empty home space must keep receiving the rest of a candidate gesture.
        return handled || candidate
    }
}
