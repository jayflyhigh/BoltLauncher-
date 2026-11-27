package app.olauncher.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

class BoltSearchInput @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatEditText(context, attrs) {
    private val useLegacyCursor = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    private val cursorWidth = 2f * resources.displayMetrics.density
    private var blinkStart = 0L
    private val cursorPaint = android.graphics.Paint().apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    private var appliedCursorColor: Int? = null

    init {
        // Android owns the blinking caret on API 29+; older versions use the
        // lightweight invalidation-driven caret drawn below.
        isCursorVisible = !useLegacyCursor
        applyCursorColor(resolveCursorColor(thunder = false, dockColor = Color.TRANSPARENT))
    }

    var onImeCommit: (() -> Unit)? = null
    var onEmptyBackspace: (() -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && consumeEmptyBackspace()) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun consumeEmptyBackspace(): Boolean =
        text.isNullOrEmpty() && onEmptyBackspace?.invoke() == true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                (beforeLength > 0 && afterLength == 0 && consumeEmptyBackspace()) ||
                    super.deleteSurroundingText(beforeLength, afterLength)

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                (beforeLength > 0 && afterLength == 0 && consumeEmptyBackspace()) ||
                    super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

            override fun finishComposingText(): Boolean {
                return super.finishComposingText().also { if (it) onImeCommit?.invoke() }
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                return super.commitText(text, newCursorPosition).also { if (it) onImeCommit?.invoke() }
            }
        }
    }

    fun updateDockCursor(thunder: Boolean, dockColor: Int) {
        applyCursorColor(resolveCursorColor(thunder, dockColor))
    }

    /** Resolve theme colors when applied so palette/theme changes cannot leave a stale caret. */
    private fun resolveCursorColor(thunder: Boolean, dockColor: Int): Int {
        val preferred = if (thunder) {
            context.getColorFromAttr(R.attr.boltThunderCursorColor)
        } else {
            WallpaperThemeColors.from(context)?.accent
                ?: context.getColorFromAttr(R.attr.boltSearchCursorColor)
        }
        val opaqueDock = dockColor or Color.BLACK
        return if (ColorUtils.calculateContrast(preferred, opaqueDock) >= 3.0) {
            preferred
        } else if (ColorUtils.calculateLuminance(opaqueDock) > 0.179) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun applyCursorColor(color: Int) {
        if (appliedCursorColor == color) return
        appliedCursorColor = color
        cursorPaint.color = color
        blinkStart = android.os.SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, R.drawable.bolt_cursor_yellow)?.mutate()?.apply { setTint(color) }
        }
        invalidate()
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        blinkStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previous: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previous)
        blinkStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        blinkStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!useLegacyCursor || !isFocused || !hasWindowFocus() || selectionStart < 0 ||
            selectionStart != selectionEnd) return
        val textLayout = layout ?: return
        val elapsed = android.os.SystemClock.uptimeMillis() - blinkStart
        if ((elapsed / 500L) % 2L == 0L) {
            val offset = selectionStart.coerceAtMost(textLayout.text.length)
            val line = textLayout.getLineForOffset(offset)
            val left = (scrollX + compoundPaddingLeft).toFloat()
            val right = (scrollX + width - compoundPaddingRight).toFloat()
            if (right - left < cursorWidth) return
            val x = (compoundPaddingLeft + textLayout.getPrimaryHorizontal(offset))
                .coerceIn(left, right - cursorWidth)
            val originY = baseline - textLayout.getLineBaseline(0)
            val top = (originY + textLayout.getLineTop(line)).toFloat()
            val bottom = (originY + textLayout.getLineBottom(line)).toFloat()
            canvas.drawRect(x, top, x + cursorWidth, bottom, cursorPaint)
        }
        postInvalidateDelayed(500L - elapsed % 500L)
    }
}
