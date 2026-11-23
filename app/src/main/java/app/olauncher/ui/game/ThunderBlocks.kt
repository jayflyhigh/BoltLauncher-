package app.olauncher.ui.game

import java.io.Serializable
import kotlin.random.Random

internal class ThunderBlocks(
    private val random: Random = Random.Default,
    state: State? = null,
) {
    companion object {
        const val COLUMNS = 11
        const val ROWS = 19
        private const val LINES_PER_LEVEL = 8
        const val LOCK_DELAY = 350L
        const val MAX_LOCK_RESETS = 8
        private val kickOffsets = intArrayOf(0, -1, 1, -2, 2)
        private val shapes = listOf(
            listOf("####"),
            listOf("##", "##"),
            listOf(".#.", "###"),
            listOf("#..", "###"),
            listOf("..#", "###"),
            listOf(".##", "##."),
            listOf("##.", ".##"),
        )
    }

    data class Cell(val x: Int, val y: Int)
    data class Piece(val color: Int, val cells: List<Cell>)

    data class State(
        val board: IntArray,
        val activeColor: Int,
        val turns: Int,
        val nextColor: Int,
        val bag: IntArray,
        val x: Int,
        val y: Int,
        val lines: Int,
        val score: Long,
        val gameOver: Boolean,
        val lockRemaining: Long?,
        val lockResets: Int,
    ) : Serializable {
        private companion object {
            const val serialVersionUID = 1L
        }
    }

    val board = state?.board?.copyOf() ?: IntArray(COLUMNS * ROWS)
    private val bag = ArrayDeque<Int>().apply { state?.bag?.forEach { addLast(it) } }
    private var turns = state?.turns ?: 0
    private var lockResets = state?.lockResets ?: 0
    private var lockBudget = state?.lockRemaining
    private var grounded = false
    val lockRemaining get() = if (grounded) lockBudget else null
    var next = state?.let { piece(it.nextColor) } ?: takePiece()
        private set
    var active = state?.let { saved ->
        var restored = piece(saved.activeColor)
        repeat(turns) { restored = rotated(restored) }
        restored
    } ?: next
        private set
    var x = state?.x ?: 0
        private set
    var y = state?.y ?: 0
        private set
    var lines = state?.lines ?: 0
        private set
    var score = state?.score ?: 0L
        private set
    var gameOver = state?.gameOver ?: false
        private set
    var clearedRows = 0
        private set
    val clearedLastMove get() = clearedRows.countOneBits()
    val clearedFourRows get() = clearedLastMove == 4

    val level get() = lines / LINES_PER_LEVEL + 1
    val interval get() = (940L - (level - 1) * 29L).coerceAtLeast(85L)

    init {
        if (state == null) spawn() else if (!gameOver) updateGrounding()
    }

    fun snapshot(elapsed: Long = 0L) = State(
        board.copyOf(), active.color, turns, next.color, bag.toIntArray(), x, y,
        lines, score, gameOver,
        lockBudget?.let { if (grounded) (it - elapsed).coerceAtLeast(0L) else it },
        lockResets,
    )

    fun move(dx: Int): Boolean {
        if (gameOver || dx == 0 || !fits(active, x + dx, y)) return false
        x += dx
        updateGrounding(adjusted = true)
        return true
    }

    fun rotate(): Boolean {
        if (gameOver) return false
        val rotated = rotated(active)
        for (lift in 0..3) {
            for (offset in kickOffsets) {
                if (!fits(rotated, x + offset, y - lift)) continue
                x += offset
                y -= lift
                active = rotated
                turns = (turns + 1) % 4
                updateGrounding(adjusted = true)
                return true
            }
        }
        return false
    }

    private fun rotated(piece: Piece): Piece {
        val height = piece.cells.maxOf { it.y } + 1
        return piece.copy(cells = piece.cells.map { Cell(height - 1 - it.y, it.x) })
    }

    val landingY: Int
        get() {
            var target = y
            while (fits(active, x, target + 1)) target++
            return target
        }

    /** Returns true when the piece moves down a row. */
    fun step(softDrop: Boolean = false): Boolean {
        if (gameOver) return false
        val moved = fits(active, x, y + 1)
        if (moved) {
            y++
            if (softDrop) score++
        }
        updateGrounding()
        return moved
    }

    private fun updateGrounding(adjusted: Boolean = false) {
        val wasGrounded = grounded
        grounded = !fits(active, x, y + 1)
        if (!grounded) return
        if (lockBudget == null) {
            lockBudget = LOCK_DELAY
        } else if (adjusted && wasGrounded && lockResets < MAX_LOCK_RESETS) {
            lockResets++
            lockBudget = LOCK_DELAY
        }
    }

    /** Returns true only when this call locks the active piece. */
    fun elapseLockTime(elapsed: Long): Boolean {
        val remaining = lockRemaining ?: return false
        if (elapsed < remaining) {
            lockBudget = remaining - elapsed
            return false
        }
        lock()
        return true
    }

    fun drop() {
        if (gameOver) return
        val target = landingY
        score += (target - y) * 2L
        y = target
        lock()
    }

    private fun fits(piece: Piece, left: Int, top: Int): Boolean = piece.cells.all { cell ->
        val column = left + cell.x
        val row = top + cell.y
        column in 0 until COLUMNS && row in 0 until ROWS && board[row * COLUMNS + column] == 0
    }

    private fun lock() {
        active.cells.forEach { board[(y + it.y) * COLUMNS + x + it.x] = active.color }
        var write = ROWS - 1
        clearedRows = 0
        for (row in ROWS - 1 downTo 0) {
            val start = row * COLUMNS
            if ((start until start + COLUMNS).all { board[it] != 0 }) {
                clearedRows = clearedRows or (1 shl row)
                continue
            }
            if (write != row) board.copyInto(board, write * COLUMNS, start, start + COLUMNS)
            write--
        }
        board.fill(0, toIndex = clearedLastMove * COLUMNS)
        score += 120L * clearedLastMove * clearedLastMove * level
        lines += clearedLastMove
        spawn()
    }

    private fun spawn() {
        turns = 0
        lockBudget = null
        grounded = false
        lockResets = 0
        active = next
        next = takePiece()
        x = (COLUMNS - active.cells.maxOf { it.x } - 1) / 2
        y = 0
        gameOver = !fits(active, x, y)
        if (!gameOver) updateGrounding()
    }

    private fun takePiece(): Piece {
        if (bag.isEmpty()) bag.addAll(shapes.indices.shuffled(random))
        return piece(bag.removeFirst() + 1)
    }

    private fun piece(color: Int): Piece {
        val cells = shapes[color - 1].flatMapIndexed { row, line ->
            line.mapIndexedNotNull { col, mark -> if (mark == '#') Cell(col, row) else null }
        }
        return Piece(color, cells)
    }
}
