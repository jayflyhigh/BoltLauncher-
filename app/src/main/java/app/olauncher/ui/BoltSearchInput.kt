package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

class BoltSearchInput @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatEditText(context, attrs) {
    private var drawLegacyCursor = false
    private val paperNotebook = app.olauncher.data.Prefs(context).launcherTheme ==
        app.olauncher.data.Prefs.LauncherTheme.PAPER_NOTEBOOK
    private val darkCursorColor = context.getColorFromAttr(R.attr.boltThunderCursorColor)
    private val idleCursorColor = context.getColorFromAttr(R.attr.boltSearchCursorColor)
    private var blinkStart = 0L
    private val cursorPaint = android.graphics.Paint().apply { color = darkCursorColor }

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

    fun setDarkDockText(enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Let TextView handle cursor geometry, blinking, and selection on supported devices.
            textCursorDrawable = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, R.drawable.bolt_cursor_yellow)?.mutate()?.apply {
                    setTint(if (enabled) darkCursorColor else idleCursorColor)
                }
            return
        }
        val useCustomCursor = enabled || paperNotebook
        isCursorVisible = !useCustomCursor
        val color = if (enabled) darkCursorColor else idleCursorColor
        if (drawLegacyCursor == useCustomCursor && cursorPaint.color == color) return
        drawLegacyCursor = useCustomCursor
        cursorPaint.color = color
        blinkStart = android.os.SystemClock.uptimeMillis()
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
        if (!drawLegacyCursor || !isFocused || !hasWindowFocus() || selectionStart < 0 ||
            selectionStart != selectionEnd) return
        val textLayout = layout ?: return
        val elapsed = android.os.SystemClock.uptimeMillis() - blinkStart
        if ((elapsed / 500L) % 2L == 0L) {
            val offset = selectionStart.coerceAtMost(textLayout.text.length)
            val line = textLayout.getLineForOffset(offset)
            val x = compoundPaddingLeft + textLayout.getPrimaryHorizontal(offset)
            val originY = baseline - textLayout.getLineBaseline(0)
            val top = (originY + textLayout.getLineTop(line)).toFloat()
            val bottom = (originY + textLayout.getLineBottom(line)).toFloat()
            canvas.drawRect(x, top, x + 2f * resources.displayMetrics.density, bottom, cursorPaint)
        }
        postInvalidateDelayed(500L - elapsed % 500L)
    }
}
