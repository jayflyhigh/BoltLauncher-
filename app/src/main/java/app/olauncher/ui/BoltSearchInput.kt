package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr

/**
 * Search input with a theme-aware, permanently visible caret rendered by Bolt.
 * The same static caret is used in regular search, Thunder mode and Search in Apps.
 */
class BoltSearchInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    private val cursorWidth = (2f * resources.displayMetrics.density).coerceAtLeast(1f)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cursorClip = Rect()
    private var appliedCursorColor: Int? = null

    init {
        // Hide the platform caret on every Android version. Bolt draws one
        // consistent static caret itself so it cannot blink or vary by API/theme.
        isCursorVisible = false
        applyCursorColor(resolveCursorColor(Color.BLACK))
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

            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { if (it) onImeCommit?.invoke() }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { if (it) onImeCommit?.invoke() }
        }
    }

    fun updateDockCursor(thunder: Boolean, dockColor: Int) {
        applyCursorColor(resolveCursorColor(dockColor))
    }

    /** White on dark search bars; black on light search bars. */
    private fun resolveCursorColor(dockColor: Int): Int {
        val opaqueDock = ColorUtils.setAlphaComponent(dockColor, 255)
        return if (ColorUtils.calculateLuminance(opaqueDock) > 0.179) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun applyCursorColor(color: Int) {
        if (appliedCursorColor == color) return
        appliedCursorColor = color
        cursorPaint.color = color
        isCursorVisible = false
        invalidate()
    }

    private fun refreshCaret() = invalidate()

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        refreshCaret()
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        restartCursorBlink()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        restartCursorBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartCursorBlink()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // No caret while the field is unfocused, selection is active, or window hidden.
        if (!isFocused || !hasWindowFocus() || selectionStart < 0 || selectionStart != selectionEnd
        ) return

        val textLayout = layout ?: return
        val offset = selectionStart.coerceIn(0, textLayout.text.length)
        val line = textLayout.getLineForOffset(offset)

        // Layout horizontal coordinates are relative to the text origin. TextView
        // scrolls the text independently, so account for scrollX before clipping.
        val x = compoundPaddingLeft + textLayout.getPrimaryHorizontal(offset) - scrollX
        val clipLeft = compoundPaddingLeft.toFloat()
        val clipRight = (width - compoundPaddingRight).toFloat()
        if (x + cursorWidth <= clipLeft || x >= clipRight) {
            return
        }

        val originY = baseline - textLayout.getLineBaseline(0)
        val top = (originY + textLayout.getLineTop(line)).toFloat()
        val bottom = (originY + textLayout.getLineBottom(line)).toFloat()
        {
            val save = canvas.save()
            canvas.clipRect(clipLeft, extendedPaddingTop.toFloat(), clipRight,
                (height - extendedPaddingBottom).toFloat())
            canvas.drawRect(x, top, x + cursorWidth, bottom, cursorPaint)
            canvas.restoreToCount(save)
        }

    }
}
