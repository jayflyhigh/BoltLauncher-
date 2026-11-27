package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
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
 * Search input with a theme-aware caret rendered by Bolt itself.
 *
 * Drawing the caret in the view (instead of relying on TextView's private native
 * cursor drawable) keeps it visible and blinking consistently across Android
 * versions, themes, regular search, Thunder mode, and Search in Apps mode.
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
    private var blinkStart = SystemClock.uptimeMillis()
    private var appliedCursorColor: Int? = null

    init {
        // Prefer Android's native EditText caret: it owns focus, selection, IME,
        // scrolling and blink scheduling, so the cursor cannot disappear when the
        // search mode or theme changes. Keep the custom renderer only for API 24–28.
        isCursorVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
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

            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { if (it) onImeCommit?.invoke() }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { if (it) onImeCommit?.invoke() }
        }
    }

    fun updateDockCursor(thunder: Boolean, dockColor: Int) {
        applyCursorColor(resolveCursorColor(thunder, dockColor))
    }

    /** Resolve theme colors at runtime so theme and wallpaper changes update the caret. */
    private fun resolveCursorColor(thunder: Boolean, dockColor: Int): Int {
        val preferred = if (thunder) {
            context.getColorFromAttr(R.attr.boltThunderCursorColor)
        } else {
            WallpaperThemeColors.from(context)?.accent
                ?: context.getColorFromAttr(R.attr.boltSearchCursorColor)
        }
        val opaqueDock = ColorUtils.setAlphaComponent(dockColor, 255)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cursorHeight = (textSize * 1.2f).toInt().coerceAtLeast(1)
            textCursorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setSize(cursorWidth.toInt().coerceAtLeast(1), cursorHeight)
                setColor(color)
            }
            isCursorVisible = true
        } else {
            isCursorVisible = false
        }
        restartCursorBlink()
    }

    private fun restartCursorBlink() {
        blinkStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        restartCursorBlink()
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

        // Android 10+ draws and blinks the native caret. The custom fallback is
        // needed only on older Android versions where the runtime setter is absent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return

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
            postInvalidateDelayed(500L)
            return
        }

        val originY = baseline - textLayout.getLineBaseline(0)
        val top = (originY + textLayout.getLineTop(line)).toFloat()
        val bottom = (originY + textLayout.getLineBottom(line)).toFloat()
        val elapsed = (SystemClock.uptimeMillis() - blinkStart).coerceAtLeast(0L)
        val visible = (elapsed / 500L) % 2L == 0L

        if (visible) {
            val save = canvas.save()
            canvas.clipRect(clipLeft, extendedPaddingTop.toFloat(), clipRight,
                (height - extendedPaddingBottom).toFloat())
            canvas.drawRect(x, top, x + cursorWidth, bottom, cursorPaint)
            canvas.restoreToCount(save)
        }

        // Keep the caret animation alive even when the text itself is unchanged.
        postInvalidateDelayed(500L - elapsed % 500L)
    }
}
