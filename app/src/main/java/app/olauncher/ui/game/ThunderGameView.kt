package app.olauncher.ui.game

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import androidx.core.graphics.ColorUtils
import app.olauncher.ui.WallpaperThemeColors
import app.olauncher.helper.getColorFromAttr
import kotlin.math.abs
import kotlin.math.max

internal class ThunderGameView(
    context: Context,
    restoredState: ThunderBlocks.State? = null,
    private val exit: () -> Unit,
) : LinearLayout(context) {
    private val settings = context.getSharedPreferences("thunder_blocks", Context.MODE_PRIVATE)
    private var game = ThunderBlocks(state = restoredState)
    private var best = max(settings.getLong("best", 0L), game.score)
    private var paused = restoredState != null
    private var musicEnabled = settings.getBoolean("music", true)
    private var soundEnabled = settings.getBoolean("sound", true)
    private val board = ThunderBoardView(context)
    private val preview = ThunderBoardView(context, preview = true)
    private val density = resources.displayMetrics.density
    private val wallpaperColors = WallpaperThemeColors.from(context)
    private val surfaceColor = wallpaperColors?.dock ?: context.getColorFromAttr(R.attr.boltDockColor)
    private val opaqueSurface = surfaceColor or Color.BLACK
    private val textColor = readableColor(context.getColorFromAttr(R.attr.boltResultTextColor))
    private val accent = readableColor(wallpaperColors?.accent ?: context.getColorFromAttr(R.attr.boltSearchCursorColor))
    private val stats = label()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false
    private var focusBlocked = false
    private var music: MediaPlayer? = null
    private var musicReady = false
    private var musicPlaying = false
    private var exitConfirmation: AlertDialog? = null
    private val sounds = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(audioAttributes).build()
    private val lockSound = sounds.load(context, R.raw.thunder_lock, 1)
    private val clearSound = sounds.load(context, R.raw.thunder_clear, 1)
    private val rotateSound = sounds.load(context, R.raw.thunder_rotate, 1)
    private enum class Drag { HORIZONTAL, DOWN }
    private var drag: Drag? = null
    private var trackingTouch = false
    private var tapCandidate = false
    private var blockedDrag = false
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
        focusBlocked = false
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
    private val dockResumeButton = button(R.string.thunder_game_resume, ::resumeGame)
    private val dockExitButton = button(R.string.thunder_game_exit, exit)
    private val running get() = !paused && !game.gameOver
    private var lockClock: Long? = null
    private val tick = Runnable {
        if (running) {
            if (lockClock != null) settleLockClock() else {
                game.step()
                board.invalidate()
            }
            scheduleTick()
        }
    }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        clipChildren = false
        board.setOnClickListener { rotatePiece() }
        board.setOnTouchListener { _, event -> handleBoardTouch(event) }
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
        }
        rail.addView(label(R.string.thunder_game_title).apply {
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!settings.getBoolean("controls_hint_seen", false)) {
            rail.addView(label(R.string.thunder_game_controls_hint).apply {
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        rail.addView(stats)
        rail.addView(label(R.string.thunder_game_next))
        rail.addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
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
            addView(dockResumeButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(dockExitButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(dock, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        bindGame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!settings.getBoolean("controls_hint_seen", false)) {
            settings.edit().putBoolean("controls_hint_seen", true).apply()
        }
        updateMusic()
        scheduleTick()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateMusic() else suspendMusic()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        lockClock = null
        endBoardTouch()
        exitConfirmation?.dismiss()
        saveBest()
        abandonMusicFocus()
        releaseMusic()
        sounds.release()
        super.onDetachedFromWindow()
    }

    fun snapshot(): ThunderBlocks.State =
        game.snapshot(lockClock?.let { SystemClock.uptimeMillis() - it } ?: 0L)

    fun handleBack() {
        when {
            game.gameOver -> exit()
            !paused -> pauseGame()
            exitConfirmation == null -> {
                exitConfirmation = AlertDialog.Builder(context)
                    .setMessage(R.string.thunder_game_confirm_exit)
                    .setNegativeButton(R.string.thunder_game_resume) { _, _ -> resumeGame() }
                    .setPositiveButton(R.string.thunder_game_exit) { _, _ -> exit() }
                    .create().apply {
                        setOnDismissListener { exitConfirmation = null }
                        show()
                    }
            }
        }
    }

    fun pauseGame() {
        if (!running) return
        settleLockClock()
        paused = true
        endBoardTouch()
        scheduleTick()
        saveBest()
        updateMusic()
        updateControls()
    }

    private fun resumeGame() {
        paused = false
        focusBlocked = false
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
            board.strike(game.clearedRows, game.clearedFourRows)
        } else {
            play(lockSound)
        }
        if (game.gameOver) {
            endBoardTouch()
            saveBest()
            updateMusic()
        }
        bindGame()
    }

    private fun rotatePiece() {
        if (!running) return
        val locked = settleLockClock()
        if (!running) return
        val wasGrounded = game.lockRemaining != null
        val rotated = game.rotate()
        board.showInputFeedback(blocked = !rotated)
        if (rotated) play(rotateSound)
        if (locked || wasGrounded || game.lockRemaining != null) scheduleTick()
    }

    private fun hardDrop() {
        if (!running) return
        settleLockClock()
        if (!running) return
        game.drop()
        onLock()
        board.showInputFeedback(blocked = false)
        scheduleTick()
    }

    private fun endBoardTouch() {
        trackingTouch = false
        board.isPressed = false
    }

    private fun handleBoardTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = running
                tapCandidate = running
                board.isPressed = running
                blockedDrag = false
                drag = null
                anchorX = event.x
                anchorY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> endBoardTouch()
            MotionEvent.ACTION_MOVE -> if (trackingTouch && running) moveWithFinger(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (trackingTouch && running) {
                    moveWithFinger(event.x, event.y)
                    if (tapCandidate) board.performClick()
                }
                endBoardTouch()
            }
        }
        return true
    }

    private fun moveWithFinger(x: Float, y: Float) {
        val dx = x - anchorX
        val dy = y - anchorY
        val distance = max(abs(dx), abs(dy))
        val cell = board.cellSize
        if (distance <= touchSlop || cell <= 0f) return
        tapCandidate = false
        board.isPressed = false
        val direction = when {
            abs(dx) >= abs(dy) -> Drag.HORIZONTAL
            dy > 0 -> Drag.DOWN
            else -> {
                // Upward travel has no game action. Reset the baseline for the next gesture segment.
                anchorX = x
                anchorY = y
                drag = null
                blockedDrag = false
                return
            }
        }
        val delta = if (direction == Drag.HORIZONTAL) dx else dy
        val changedDirection = direction != drag
        val steps = (abs(delta) / cell).toInt().coerceAtLeast(if (changedDirection) 1 else 0)
        if (steps == 0) return
        drag = direction
        val consumed = minOf(steps * cell, abs(delta))
        if (direction == Drag.HORIZONTAL) {
            anchorX += if (delta > 0) consumed else -consumed
            anchorY = y
        } else {
            anchorX = x
            anchorY += consumed
        }

        val locked = settleLockClock()
        if (!running) return
        val wasGrounded = game.lockRemaining != null
        val maxSteps = if (direction == Drag.HORIZONTAL) ThunderBlocks.COLUMNS else ThunderBlocks.ROWS
        var moved = false
        var blocked = false
        val horizontalStep = if (delta > 0) 1 else -1
        for (step in 0 until steps.coerceAtMost(maxSteps)) {
            val accepted = if (direction == Drag.HORIZONTAL) game.move(horizontalStep)
                else game.step(softDrop = true)
            if (!accepted) {
                blocked = true
                break
            }
            moved = true
        }
        if (moved && direction == Drag.DOWN) {
            best = max(best, game.score)
            updateStats()
        }
        if (moved) board.invalidate()
        if (blocked && (!blockedDrag || changedDirection)) board.showInputFeedback(blocked = true)
        blockedDrag = blocked
        if (locked || wasGrounded || game.lockRemaining != null) scheduleTick()
    }

    private fun settleLockClock(): Boolean {
        val started = lockClock ?: return false
        lockClock = null
        if (!game.elapseLockTime(SystemClock.uptimeMillis() - started)) return false
        onLock()
        return true
    }

    private fun scheduleTick() {
        removeCallbacks(tick)
        lockClock = null
        if (!running || !isAttachedToWindow) return
        val remaining = game.lockRemaining
        if (remaining != null) lockClock = SystemClock.uptimeMillis()
        postDelayed(tick, remaining ?: game.interval)
    }

    private fun updateControls() {
        updateStats()
        pauseButton.setText(when {
            game.gameOver -> R.string.thunder_game_restart
            paused -> R.string.thunder_game_resume
            else -> R.string.thunder_game_pause
        })
        val showPauseActions = paused && !game.gameOver
        dockResumeButton.visibility = if (showPauseActions) VISIBLE else GONE
        dockExitButton.visibility = dockResumeButton.visibility
        dockDropButton.visibility = if (showPauseActions) GONE else VISIBLE
        dropButton.visibility = dockDropButton.visibility
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

    private val wantsMusic get() = musicEnabled && running && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE

    private fun updateMusic() {
        if (!wantsMusic) {
            suspendMusic()
            return
        }
        if (focusBlocked) return
        if (focusListener == null) requestMusicFocus()
        if (!focusGranted) return
        if (musicReady) {
            if (!musicPlaying) {
                music?.start()
                musicPlaying = true
            }
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
                    if (music !== it) return@setOnPreparedListener
                    musicReady = true
                    updateMusic()
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (music === failed) {
                        releaseMusic()
                        abandonMusicFocus()
                    }
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                releaseMusic()
                abandonMusicFocus()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestMusicFocus() {
        val listener = object : AudioManager.OnAudioFocusChangeListener {
            override fun onAudioFocusChange(change: Int) {
                if (focusListener !== this) return
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusGranted = true
                        updateMusic()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        focusGranted = false
                        pauseMusic()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusBlocked = true
                        abandonMusicFocus()
                        releaseMusic()
                    }
                }
            }
        }
        focusListener = listener
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            focusBlocked = true
            abandonMusicFocus()
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonMusicFocus() {
        val listener = focusListener ?: return
        focusListener = null
        focusGranted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            audioManager.abandonAudioFocus(listener)
        }
    }

    private fun pauseMusic() {
        if (!musicPlaying) return
        music?.pause()
        musicPlaying = false
    }

    private fun suspendMusic() {
        pauseMusic()
        abandonMusicFocus()
    }

    private fun releaseMusic() {
        val player = music
        music = null
        musicReady = false
        musicPlaying = false
        player?.release()
    }

    private fun play(sound: Int) {
        if (soundEnabled && isAttachedToWindow) sounds.play(sound, 0.55f, 0.55f, 1, 0, 1f)
    }

    private fun saveBest() {
        if (best > settings.getLong("best", 0L)) settings.edit().putLong("best", best).apply()
    }

    private fun readableColor(preferred: Int): Int =
        when {
            ColorUtils.calculateContrast(preferred, opaqueSurface) >= 4.5 -> preferred
            ColorUtils.calculateLuminance(opaqueSurface) > 0.179 -> Color.BLACK
            else -> Color.WHITE
        }

    private fun surface() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(surfaceColor)
        setStroke(dp(1), wallpaperColors?.outline ?: context.getColorFromAttr(R.attr.boltPanelOutlineColor))
    }

    private fun label(textId: Int = 0) = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(textColor)
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
        setTextColor(textColor)
        setBackgroundResource(R.drawable.bolt_search_press)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}
