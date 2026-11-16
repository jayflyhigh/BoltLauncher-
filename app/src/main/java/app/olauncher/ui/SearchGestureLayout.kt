package app.olauncher.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import app.olauncher.R
import kotlin.math.abs

/** Observe the whole dock, including its input, without stealing taps or horizontal editing. */
class SearchGestureLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    var canSwitchSearch: (() -> Boolean)? = null
    var switchSearch: (() -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var candidate = false
    private var consumed = false
    private val bounds = Rect()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                consumed = false
                // Keep normal scrolling/selection gestures on visible result rows.
                val results = findViewById<android.view.View>(R.id.recyclerView)
                val onResults = results?.isShown == true && results.getGlobalVisibleRect(bounds) &&
                    bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                candidate = !onResults && canSwitchSearch?.invoke() == true
            }
            MotionEvent.ACTION_POINTER_DOWN -> candidate = false
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (!consumed && candidate && -dy > 64f * resources.displayMetrics.density &&
                    -dy > abs(dx) && canSwitchSearch?.invoke() == true) {
                    consumed = true
                    candidate = false
                    // Cancel the original target so releasing a swipe cannot click or launch it.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    switchSearch?.invoke()
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
