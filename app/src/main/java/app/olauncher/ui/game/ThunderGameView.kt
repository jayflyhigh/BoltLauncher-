package app.olauncher.ui.game

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr
import app.olauncher.ui.LauncherMotion
import kotlin.math.abs
import kotlin.math.max

internal class ThunderGameView(
    context: Context,
    private val exit: () -> Unit,
) : LinearLayout(context) {
    private val settings = context.getSharedPreferences("thunder_blocks", Context.MODE_PRIVATE)
    private var game = ThunderBlocks()
    private var best = settings.getLong("best", 0L)
    private var paused = false
    private var musicEnabled = settings.getBoolean("music", true)
    private var soundEnabled = settings.getBoolean("sound", true)
    private val board = ThunderBoardView(context)
    private val preview = ThunderBoardView(context, preview = true)
    private val stats = label()
    private val density = resources.displayMetrics.density
    private val accent = context.getColor(R.color.bolt_search_yellow)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var music: MediaPlayer? = null
    private var musicReady = false
    private val sounds = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(audioAttributes).build()
    private val lockSound = sounds.load(context, R.raw.thunder_lock, 1)
    private val clearSound = sounds.load(context, R.raw.thunder_clear, 1)
    private val rotateSound = sounds.load(context, R.raw.thunder_rotate, 1)
    private enum class Drag { HORIZONTAL, DOWN }
    private var drag: Drag? = null
    private var trackingTouch = false
    private var anchorX = 0f
    private var anchorY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pauseButton = button(R.string.thunder_game_pause) {
        when {
            game.gameOver -> restart()
            paused -> resumeGame()
            else -> pauseGame()
        }
    }
    private val dockDropButton = button(R.string.thunder_game_dock_drop, ::hardDrop)
    private val dropButton = button(R.string.thunder_game_drop_icon, ::hardDrop)
    private val musicButton = button(R.string.thunder_game_music_on) {
        musicEnabled = !musicEnabled
        settings.edit().putBoolean("music", musicEnabled).apply()
        updateMusic()
        updateControls()
    }
    private val soundButton = button(R.string.thunder_game_sound_on) {
        soundEnabled = !soundEnabled
        settings.edit().putBoolean("sound", soundEnabled).apply()
        if (soundEnabled) play(rotateSound)
        updateControls()
    }
    private val running get() = !paused && !game.gameOver
    private val tick = Runnable {
        if (running) {
            if (game.step()) onLock() else board.invalidate()
            scheduleTick()
        }
    }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        clipChildren = false
        setOnClickListener { rotatePiece() }
        setPadding(dp(16), dp(8), dp(16), dp(8))

        val field = LinearLayout(context)
        field.gravity = Gravity.CENTER_VERTICAL
        field.addView(board, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val railScroll = ScrollView(context).apply { isFillViewport = false }
        val rail = LinearLayout(context).apply {
            orientation = VERTICAL
            background = surface()
            setPadding(dp(5), dp(4), dp(5), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { rotatePiece() }
        }
        rail.addView(label(R.string.thunder_game_title).apply {
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })
        rail.addView(stats)
        rail.addView(label(R.string.thunder_game_next))
        rail.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        listOf(pauseButton, musicButton, soundButton, button(R.string.thunder_game_exit, exit)).forEach {
            rail.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        railScroll.addView(rail)
        field.addView(railScroll, LayoutParams(dp(110), LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
        })
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val dock = LinearLayout(context).apply {
            background = surface()
            addView(dockDropButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(dropButton.apply {
                setTextColor(accent)
                textSize = 20f
                contentDescription = context.getString(R.string.thunder_game_drop)
            }, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        }
        addView(dock, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        bindGame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMusic()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        saveBest()
        music?.release()
        music = null
        musicReady = false
        sounds.release()
        super.onDetachedFromWindow()
    }

    fun pauseGame() {
        paused = true
        scheduleTick()
        saveBest()
        updateMusic()
        updateControls()
    }

    private fun resumeGame() {
        paused = false
        updateMusic()
        scheduleTick()
        updateControls()
    }

    private fun restart() {
        saveBest()
        game = ThunderBlocks()
        board.resetStrike()
        bindGame()
        resumeGame()
    }

    private fun bindGame() {
        board.game = game
        preview.game = game
        board.invalidate()
        preview.invalidate()
        updateControls()
    }

    private fun onLock() {
        best = max(best, game.score)
        if (game.clearedLastMove > 0) {
            play(clearSound)
            if (LauncherMotion.isEnabled(context)) board.strike(game.clearedRows)
        } else {
            play(lockSound)
        }
        if (game.gameOver) {
            saveBest()
            updateMusic()
        }
        bindGame()
    }

    private fun rotatePiece() {
        if (running && game.rotate()) {
            play(rotateSound)
            board.invalidate()
        }
    }

    private fun hardDrop() {
        if (!running) return
        game.drop()
        onLock()
        scheduleTick()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = running
                drag = null
                anchorX = event.x
                anchorY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> trackingTouch = false
            MotionEvent.ACTION_MOVE -> {
                if (trackingTouch && running) {
                    val dx = event.x - anchorX
                    val dy = event.y - anchorY
                    if (drag == null) {
                        drag = when {
                            abs(dx) > touchSlop && abs(dx) >= abs(dy) -> Drag.HORIZONTAL
                            dy > touchSlop -> Drag.DOWN
                            else -> null
                        }
                        if (drag != null) {
                            // A drag that starts on a button must not click it on release.
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                    }
                    if (drag != null) moveWithFinger(dx, dy)
                }
                if (drag != null) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false
                if (drag != null) {
                    drag = null
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun moveWithFinger(dx: Float, dy: Float) {
        val cell = board.cellSize
        if (cell <= 0f) return
        when (drag) {
            Drag.HORIZONTAL -> {
                val shift = (dx / cell).toInt()
                if (shift == 0) return
                repeat(abs(shift).coerceAtMost(ThunderBlocks.COLUMNS)) { game.move(if (shift > 0) 1 else -1) }
                anchorX += shift * cell
            }
            Drag.DOWN -> {
                if (dy < 0) anchorY += dy
                val down = (dy / cell).toInt()
                if (down <= 0) return
                anchorY += down * cell
                for (step in 0 until down.coerceAtMost(ThunderBlocks.ROWS)) {
                    if (game.step(softDrop = true)) {
                        onLock()
                        scheduleTick()
                        break
                    }
                }
                best = max(best, game.score)
                updateStats()
            }
            null -> return
        }
        board.invalidate()
    }

    private fun scheduleTick() {
        removeCallbacks(tick)
        if (running && isAttachedToWindow) postDelayed(tick, game.interval)
    }

    private fun updateControls() {
        updateStats()
        pauseButton.setText(when {
            game.gameOver -> R.string.thunder_game_restart
            paused -> R.string.thunder_game_resume
            else -> R.string.thunder_game_pause
        })
        dockDropButton.isEnabled = running
        dropButton.isEnabled = running
        musicButton.setText(if (musicEnabled) R.string.thunder_game_music_on else R.string.thunder_game_music_off)
        soundButton.setText(if (soundEnabled) R.string.thunder_game_sound_on else R.string.thunder_game_sound_off)
    }

    private fun updateStats() {
        val values = context.getString(R.string.thunder_game_stats, game.level, game.score, game.lines, best)
        val status = when {
            game.gameOver -> R.string.thunder_game_over
            paused -> R.string.thunder_game_paused
            else -> null
        }
        stats.text = if (status == null) values else context.getString(status) + "\n" + values
    }

    private fun updateMusic() {
        if (!musicEnabled || !running || !isAttachedToWindow) {
            if (musicReady) music?.pause()
            return
        }
        if (musicReady) {
            music?.start()
        } else if (music == null) {
            val player = MediaPlayer()
            music = player
            try {
                player.setAudioAttributes(audioAttributes)
                resources.openRawResourceFd(R.raw.thunder_music).use { asset ->
                    player.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
                }
                player.isLooping = true
                player.setVolume(0.35f, 0.35f)
                player.setOnPreparedListener {
                    musicReady = true
                    updateMusic()
                }
                player.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    music = null
                    musicReady = false
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                player.release()
                music = null
            }
        }
    }

    private fun play(sound: Int) {
        if (soundEnabled && isAttachedToWindow) sounds.play(sound, 0.55f, 0.55f, 1, 0, 1f)
    }

    private fun saveBest() {
        if (best > settings.getLong("best", 0L)) settings.edit().putLong("best", best).apply()
    }

    private fun surface() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(context.getColorFromAttr(R.attr.boltDockColor))
        setStroke(dp(1), context.getColorFromAttr(R.attr.boltPanelOutlineColor))
    }

    private fun label(textId: Int = 0) = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        if (textId != 0) setText(textId)
    }

    private fun button(textId: Int, action: () -> Unit) = Button(context).apply {
        setText(textId)
        textSize = 13f
        isAllCaps = false
        minimumHeight = dp(44)
        minHeight = dp(44)
        minWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.bolt_search_press)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}
