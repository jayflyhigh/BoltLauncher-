package app.olauncher.ui.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
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
    private val density = resources.displayMetrics.density
    private val surfaceColor = ARCADE_LCD_COLOR
    private val board = ThunderBoardView(context, surfaceColor)
    private val preview = ThunderBoardView(context, surfaceColor, preview = true)
    private val textColor = 0xFF202B27.toInt()
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
    private var optionsDialog: AlertDialog? = null
    private var loadDialog: AlertDialog? = null
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
        setBackgroundColor(surfaceColor)
        val field = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(board, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(ScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    addView(stats)
                    addView(label(R.string.thunder_game_next))
                    addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
                })
            }, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
        }
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        bindGame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
        exitConfirmation?.dismiss()
        optionsDialog?.dismiss()
        loadDialog?.dismiss()
        saveBest()
        abandonMusicFocus()
        releaseMusic()
        sounds.release()
        super.onDetachedFromWindow()
    }

    private fun stateKey(slot: Int) = "save_slot_${slot}_state"
    private fun imageKey(slot: Int) = "save_slot_${slot}_preview"

    private fun encodeSave(state: ThunderBlocks.State): String {
        val bytes = ByteArrayOutputStream().use { stream ->
            ObjectOutputStream(stream).use { it.writeObject(state) }
            stream.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeSave(value: String): ThunderBlocks.State? = try {
        val bytes = Base64.decode(value, Base64.DEFAULT)
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as? ThunderBlocks.State }
    } catch (_: Exception) { null }

    private fun saveStateSlot(slot: Int) {
        if (!paused) pauseGame()
        val width = board.width.coerceAtLeast(1)
        val height = board.height.coerceAtLeast(1)
        val shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        board.draw(Canvas(shot))
        val previewBytes = ByteArrayOutputStream().use { stream ->
            shot.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.toByteArray()
        }
        shot.recycle()
        settings.edit()
            .putString(stateKey(slot), encodeSave(snapshot()))
            .putString(imageKey(slot), Base64.encodeToString(previewBytes, Base64.NO_WRAP))
            .apply()
        android.widget.Toast.makeText(context, "Game saved in slot $slot", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showSavedStates() {
        val available = (1..2).filter { settings.contains(stateKey(it)) }
        if (available.isEmpty()) {
            android.widget.Toast.makeText(context, "No saved games yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cards = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        available.forEach { slot ->
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = surface()
                setOnClickListener {
                    val encoded = settings.getString(stateKey(slot), null)
                    val restored = encoded?.let(::decodeSave)
                    if (restored == null) {
                        android.widget.Toast.makeText(context, "Save slot $slot is damaged", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        game = ThunderBlocks(state = restored)
                        paused = true
                        lockClock = null
                        board.resetStrike()
                        bindGame()
                        updateMusic()
                        scheduleTick()
                    }
                    loadDialog?.dismiss()
                }
            }
            val image = ImageView(context).apply {
                val encoded = settings.getString(imageKey(slot), null)
                if (encoded != null) {
                    try {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    } catch (_: Exception) { }
                }
                layoutParams = LayoutParams(dp(112), dp(156))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Screenshot preview for Thunder Blocks save slot $slot"
            }
            card.addView(image)
            card.addView(label().apply { text = "Save slot $slot" })
            cards.addView(card, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        loadDialog = AlertDialog.Builder(context)
            .setTitle("Load saved game")
            .setView(cards)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        loadDialog?.show()
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
        scheduleTick()
        saveBest()
        updateMusic()
        updateStats()
    }

    private fun resumeGame() {
        paused = false
        focusBlocked = false
        updateMusic()
        scheduleTick()
        updateStats()
    }

    fun confirmRestart() {
        val wasRunning = running
        pauseGame()
        optionsDialog = AlertDialog.Builder(context)
            .setMessage(R.string.arcade_confirm_reset)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if (wasRunning) resumeGame()
            }
            .setOnCancelListener { if (wasRunning) resumeGame() }
            .setPositiveButton(R.string.thunder_game_restart) { _, _ -> restart() }
            .show()
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
        updateStats()
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
            saveBest()
            updateMusic()
        }
        bindGame()
    }

    fun rotatePiece() {
        if (!running) return
        val locked = settleLockClock()
        if (!running) return
        val wasGrounded = game.lockRemaining != null
        val rotated = game.rotate()
        board.showInputFeedback(blocked = !rotated)
        if (rotated) play(rotateSound)
        if (locked || wasGrounded || game.lockRemaining != null) scheduleTick()
    }

    fun hardDrop() {
        if (!running) return
        settleLockClock()
        if (!running) return
        game.drop()
        onLock()
        board.showInputFeedback(blocked = false)
        scheduleTick()
    }

    fun movePiece(horizontal: Int) {
        if (!running) return
        val locked = settleLockClock()
        if (!running) return
        val wasGrounded = game.lockRemaining != null
        val moved = if (horizontal == 0) game.step(softDrop = true) else game.move(horizontal)
        if (moved && horizontal == 0) {
            best = max(best, game.score)
            updateStats()
        }
        if (moved) board.invalidate() else board.showInputFeedback(blocked = true)
        if (locked || wasGrounded || game.lockRemaining != null) scheduleTick()
    }

    fun togglePlay() {
        when {
            game.gameOver -> restart()
            paused -> resumeGame()
            else -> pauseGame()
        }
    }

    fun showOptions() {
        pauseGame()
        val actions = listOf<Pair<String, () -> Unit>>(
            context.getString(if (game.gameOver) R.string.thunder_game_restart else R.string.thunder_game_resume) to ::togglePlay,
            context.getString(R.string.arcade_save_one) to { saveStateSlot(1) },
            context.getString(R.string.arcade_save_two) to { saveStateSlot(2) },
            context.getString(R.string.arcade_load) to ::showSavedStates,
            context.getString(if (musicEnabled) R.string.thunder_game_music_on else R.string.thunder_game_music_off) to {
                musicEnabled = !musicEnabled
                focusBlocked = false
                settings.edit().putBoolean("music", musicEnabled).apply()
                updateMusic()
            },
            context.getString(if (soundEnabled) R.string.thunder_game_sound_on else R.string.thunder_game_sound_off) to {
                soundEnabled = !soundEnabled
                settings.edit().putBoolean("sound", soundEnabled).apply()
                if (soundEnabled) play(rotateSound)
            },
            context.getString(R.string.arcade_return) to exit,
        )
        optionsDialog = AlertDialog.Builder(context)
            .setTitle(R.string.arcade_options)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun surface() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(surfaceColor)
        setStroke(dp(1), textColor)
    }

    private fun label(textId: Int = 0) = TextView(context).apply {
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setTextColor(textColor)
        if (textId != 0) setText(textId)
    }


    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}
