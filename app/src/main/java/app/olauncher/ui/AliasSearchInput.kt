package app.olauncher.ui

import android.content.Context
import android.annotation.SuppressLint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.appcompat.widget.SearchView

/** Receives empty/start-of-query Backspace from both software and physical keyboards. */
// SearchView requires its SearchAutoComplete subclass in the custom layout (it casts internally).
// Keep this exception local to the input that supplies alias undo and the thunder cursor.
@SuppressLint("RestrictedApi")
class AliasSearchInput @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SearchView.SearchAutoComplete(context, attrs) {
    private var thunderCursor = false
    private var blinkStart = 0L
    private val cursorPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }

    fun setThunderCursor(enabled: Boolean) {
        thunderCursor = enabled
        // Hide the framework cursor entirely in thunder mode.
        isCursorVisible = !enabled
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
        if (!thunderCursor || !isFocused || !hasWindowFocus() || selectionStart < 0 ||
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

    var dismissContactSelection: (() -> Boolean)? = null
    private var consumingBack = false

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                consumingBack = dismissContactSelection?.invoke() == true
            }
            if (consumingBack) {
                if (event.action == KeyEvent.ACTION_UP) consumingBack = false
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /** Called only for a single printable key; the fragment guards contacts mode and selection. */
    var contactShortcutKey: ((Char) -> Boolean)? = null
    private fun handleContactShortcut(text: CharSequence?): Boolean =
        text?.trim()?.singleOrNull()?.takeUnless { it.isISOControl() }
            ?.let { contactShortcutKey?.invoke(it) } == true

    var submitEmpty: (() -> Boolean)? = null
    /** Contacts mode always owns Enter, including Gboard's IME action. */
    var submitContacts: (() -> Boolean)? = null
    private var consumingEnter = false

    private fun submitEmptyQuery(): Boolean = dismissContactSelection?.invoke() == true ||
        (text.isNullOrEmpty() && submitEmpty?.invoke() == true)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode != KeyEvent.KEYCODE_ENTER && event.keyCode != KeyEvent.KEYCODE_DEL &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            event.unicodeChar > 0 && handleContactShortcut(event.unicodeChar.toChar().toString())) return true
        if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) consumingEnter =
                    submitContacts?.invoke() == true || submitEmptyQuery()
                if (consumingEnter) return true
            } else if (event.action == KeyEvent.ACTION_UP && consumingEnter) {
                consumingEnter = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    var undoAlias: (() -> Boolean)? = null

    private fun undoAtStart(): Boolean =
        selectionStart == 0 && selectionEnd == 0 && undoAlias?.invoke() == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && undoAtStart()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (handleContactShortcut(text)) return true
                return super.commitText(text, newCursorPosition)
            }
            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int,
                textAttribute: TextAttribute?,
            ): Boolean {
                // Swipe keyboards can commit a shortcut together with an automatic space.
                if (handleContactShortcut(text)) return true
                return super.commitText(text, newCursorPosition, textAttribute)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if ((editorAction == EditorInfo.IME_ACTION_SEARCH ||
                        editorAction == EditorInfo.IME_ACTION_DONE ||
                        editorAction == EditorInfo.IME_ACTION_GO ||
                        editorAction == EditorInfo.IME_NULL) &&
                    (submitContacts?.invoke() == true || submitEmptyQuery())) return true
                return super.performEditorAction(editorAction)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && undoAtStart()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && undoAtStart()) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && undoAtStart()) return true
                return super.sendKeyEvent(event)
            }
        }
    }
}
