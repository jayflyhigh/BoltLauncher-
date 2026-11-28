package app.olauncher.ui.game

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.olauncher.R
import kotlin.math.roundToInt

internal class KeychainArcadeView(
    context: Context,
    restoredState: ThunderBlocks.State?,
    private val exit: () -> Unit,
) : FrameLayout(context) {
    private enum class Control(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val caption: Int? = null,
        val symbol: String = "",
    ) {
        START(.15f, .22f, .09f, .19f, R.string.arcade_start_label),
        RESET(.37f, .22f, .025f, .044f, R.string.arcade_reset),
        DROP(.59f, .22f, .09f, .19f, R.string.arcade_drop_label),
        POWER(.87f, .22f, .09f, .19f, R.string.arcade_power_label),
        LEFT(.24f, .53f, .13f, .23f, symbol = "◀"),
        RIGHT(.50f, .53f, .13f, .23f, symbol = "▶"),
        DOWN(.37f, .79f, .13f, .23f, symbol = "▼"),
        ROTATE(.78f, .60f, .17f, .29f, R.string.arcade_rotate_label, "↻"),
    }
    private val controls = Control.entries

    private val ink = 0xFF203038.toInt()
    private val caseColor = 0xFFF2F1FB.toInt()
    private val blue = 0xFF007EAF.toInt()
    private val compact = resources.configuration.let { it.screenHeightDp < 480 || it.screenWidthDp < 320 }
    private val screen = FrameLayout(context)
    private var game: ThunderGameView? = null
    private val menu = buildMenu()
    private val shell = buildShell()

    init {
        isClickable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xAE080D13.toInt())
        addView(menu, LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(16), dp(12), dp(16), dp(12))
        })
        addView(shell, LayoutParams(-1, -1, Gravity.CENTER).apply {
            setMargins(dp(16), dp(12), dp(16), dp(12))
        })
        if (restoredState == null) showMenu() else launchGame(restoredState)
    }

    private fun buildMenu() = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), dp(380)), MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        }
    }.apply {
        background = rounded(0xFF18252F.toInt(), 24)
        val catalog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(label(context.getString(R.string.arcade_picker_title), 17f).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(-1, dp(40)))
            for (rowIndex in 0..2) {
                val row = LinearLayout(context)
                repeat(2) { column ->
                    val index = rowIndex * 2 + column
                    val tile = if (index < 5) handheldTile(index == 0) else View(context)
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(if (compact) 104 else 136), 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    })
                }
                addView(row)
            }
            addView(key(context.getString(R.string.arcade_exit), R.string.arcade_exit, action = exit),
                LinearLayout.LayoutParams(-1, dp(48)).apply {
                    setMargins(dp(4), dp(8), dp(4), 0)
                })
        }
        addView(catalog)
    }

    private fun handheldTile(playable: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = if (playable) keyBackground(caseColor, 14) else rounded(0xFF22333E.toInt(), 14)
        val thumbnail = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                val scale = minOf(width / 72f, height / 100f)
                canvas.save()
                canvas.translate((width - 72 * scale) / 2, (height - 100 * scale) / 2)
                canvas.scale(scale, scale)
                paint.color = if (playable) 0xFFE0DFF1.toInt() else 0xFF506471.toInt()
                canvas.drawRoundRect(2f, 1f, 70f, 99f, 13f, 13f, paint)
                paint.color = if (playable) ARCADE_LCD_COLOR else 0xFF31434D.toInt()
                canvas.drawRoundRect(13f, 12f, 59f, 56f, 4f, 4f, paint)
                if (playable) {
                    paint.color = ink
                    for ((x, y) in listOf(24 to 45, 31 to 45, 38 to 45, 31 to 38, 45 to 31, 45 to 38)) {
                        canvas.drawRect(x.toFloat(), y.toFloat(), x + 6f, y + 6f, paint)
                    }
                }
                paint.color = if (playable) blue else 0xFF324651.toInt()
                controls.forEach { control ->
                    val x = 4f + 64f * control.x
                    val y = 57f + 41f * control.y
                    val halfWidth = 32f * control.width
                    val halfHeight = 20.5f * control.height
                    val radius = if (control == Control.RESET || control.symbol.isNotEmpty()) halfHeight else 1f
                    canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                        radius, radius, paint)
                }
                canvas.restore()
            }
        }
        addView(thumbnail, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val name = context.getString(if (playable) R.string.thunder_game_title else R.string.arcade_empty_slot)
        addView(label(name, 12f).apply {
            if (!playable) setTextColor(0xFFBFCDD4.toInt())
            importantForAccessibility = if (playable) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        }, LinearLayout.LayoutParams(-1, dp(30)))
        if (playable) {
            contentDescription = name
            isFocusable = true
            setOnClickListener { launchGame() }
        }
    }

    private fun buildShell() = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), dp(344)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(heightMeasureSpec), dp(636)), MeasureSpec.EXACTLY),
            )
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        elevation = dp(16).toFloat()
        background = rounded(caseColor, 28).apply {
            setStroke(dp(1), 0xFFB6B8CF.toInt())
            colors = intArrayOf(Color.WHITE, 0xFFDBDDF1.toInt())
            orientation = GradientDrawable.Orientation.TL_BR
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(label(context.getString(R.string.thunder_game_title), if (compact) 12f else 16f).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
            letterSpacing = 0.12f
            setTextColor(0xFF12577F.toInt())
            setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(if (compact) 24 else 36)))
        screen.background = rounded(ARCADE_LCD_COLOR, 10).apply { setStroke(dp(2), ink) }
        screen.clipToOutline = true
        screen.setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(buildControls(), LinearLayout.LayoutParams(-1, dp(if (compact) 126 else 184)).apply {
            topMargin = dp(5)
        })
    }

    private fun buildControls(): FrameLayout {
        val panel = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                controls.forEachIndexed { index, control ->
                    val slot = getChildAt(index)
                    val hitWidth = maxOf(dp(if (control == Control.START) 68 else 44), (width * control.width).roundToInt())
                    val faceHeight = (height * control.height).roundToInt()
                    val wrapper = slot as FrameLayout
                    val caption = wrapper.getChildAt(2)
                    caption?.measure(
                        MeasureSpec.makeMeasureSpec(hitWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    )
                    val captionSpace = caption?.let { it.measuredHeight + dp(2) } ?: 0
                    val touchHeight = maxOf(dp(40), faceHeight)
                    val slotHeight = maxOf(touchHeight, faceHeight + 2 * captionSpace)
                    (slot.layoutParams as LayoutParams).apply {
                        this.width = hitWidth
                        this.height = slotHeight
                        leftMargin = (width * control.x - hitWidth / 2f).roundToInt().coerceIn(0, (width - hitWidth).coerceAtLeast(0))
                        topMargin = (height * control.y - slotHeight / 2f).roundToInt().coerceAtLeast(0)
                    }
                    val face = wrapper.getChildAt(0)
                    (wrapper.getChildAt(1).layoutParams as LayoutParams).height = touchHeight
                    (face.layoutParams as LayoutParams).apply {
                        this.width = (width * control.width).roundToInt()
                        this.height = faceHeight
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        controls.forEach { control ->
            val description = when (control) {
                Control.START -> R.string.arcade_start
                Control.RESET -> R.string.arcade_reset
                Control.DROP -> R.string.arcade_drop
                Control.POWER -> R.string.arcade_power
                Control.LEFT -> R.string.arcade_left
                Control.RIGHT -> R.string.arcade_right
                Control.DOWN -> R.string.arcade_down
                Control.ROTATE -> R.string.arcade_rotate
            }
            val slot = key("", description, repeat = control in Control.LEFT..Control.DOWN) {
                when (control) {
                    Control.START -> game?.togglePlay()
                    Control.RESET -> game?.confirmRestart()
                    Control.DROP -> game?.hardDrop()
                    Control.POWER -> showMenu()
                    Control.LEFT -> game?.movePiece(-1)
                    Control.RIGHT -> game?.movePiece(1)
                    Control.DOWN -> game?.movePiece(0)
                    Control.ROTATE -> game?.rotatePiece()
                }
            }
            if (control == Control.START) {
                slot.setOnLongClickListener { game?.showOptions(); true }
            }
            val wrapper = FrameLayout(context)
            val radius = if (control == Control.RESET || control.symbol.isNotEmpty()) 50 else 5
            wrapper.addView(label(control.symbol, 15f).apply {
                setTextColor(Color.WHITE)
                background = rounded(blue, radius).apply {
                    colors = intArrayOf(0xFF14ACEC.toInt(), 0xFF006A9F.toInt())
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(dp(1), 0xFF005C89.toInt())
                }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, 0, Gravity.CENTER))
            wrapper.addView(slot.apply {
                background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, rounded(Color.WHITE, radius))
            }, LayoutParams(-1, 0, Gravity.CENTER))
            control.caption?.let {
                wrapper.addView(label(context.getString(it), if (compact) 7f else 8f).apply {
                    setSingleLine(true)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(-1, -2, Gravity.TOP))
            }
            panel.addView(wrapper, LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        }
        return panel
    }

    private fun launchGame(state: ThunderBlocks.State? = null) {
        if (game != null) return
        val cartridgeView = ThunderGameView(context, state, ::showMenu)
        game = cartridgeView
        menu.visibility = GONE
        shell.visibility = VISIBLE
        screen.addView(cartridgeView, LayoutParams(-1, -1))
        requestFocus()
    }

    private fun showMenu() {
        game = null
        screen.removeAllViews()
        shell.visibility = GONE
        menu.visibility = VISIBLE
        requestFocus()
    }

    fun pauseGame() = game?.pauseGame()
    fun snapshot() = game?.snapshot()
    fun handleBack() = game?.handleBack() ?: exit()

    private fun key(
        caption: String,
        description: Int,
        repeat: Boolean = false,
        color: Int = blue,
        radius: Int = 8,
        action: () -> Unit,
    ) = Button(context).apply {
        text = caption
        textSize = if (caption.length == 1) 16f else 10f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        contentDescription = context.getString(description)
        background = keyBackground(color, radius)
        setOnClickListener { action() }
        if (repeat) {
            var repeated = false
            val hold = object : Runnable {
                override fun run() {
                    if (!isAttachedToWindow || !isPressed || game == null) return
                    repeated = true
                    action()
                    postDelayed(this, 75L)
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        repeated = false
                        if (game != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && repeated) {
                    isPressed = false
                    true
                } else false
            }
        }
    }

    private fun label(caption: String, size: Float) = TextView(context).apply {
        text = caption
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(ink)
        typeface = Typeface.MONOSPACE
    }

    private fun keyBackground(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x45FFFFFF),
        rounded(color, radius),
        null,
    )

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

internal val ARCADE_LCD_COLOR = 0xFFB1B7A6.toInt()
