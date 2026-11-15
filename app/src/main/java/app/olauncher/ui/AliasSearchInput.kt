package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.SearchView

/** Receives empty/start-of-query Backspace from both software and physical keyboards. */
class AliasSearchInput @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SearchView.SearchAutoComplete(context, attrs) {
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
