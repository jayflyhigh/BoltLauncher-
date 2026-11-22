package app.olauncher.ui.game

import kotlin.random.Random

internal class ThunderBlocks(private val random: Random = Random.Default) {
    companion object {
        const val COLUMNS = 11
        const val ROWS = 19
        private const val LINES_PER_LEVEL = 8
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

    val board = IntArray(COLUMNS * ROWS)
    private val bag = ArrayDeque<Int>()
    var next = takePiece()
        private set
    var active = next
        private set
    var x = 0
        private set
    var y = 0
        private set
    var lines = 0
        private set
    var score = 0L
        private set
    var gameOver = false
        private set
    var clearedRows = 0
        private set
    val clearedLastMove get() = clearedRows.countOneBits()

    val level get() = lines / LINES_PER_LEVEL + 1
    val interval get() = (940L - (level - 1) * 29L).coerceAtLeast(85L)

    init { spawn() }

    fun move(dx: Int): Boolean {
        if (gameOver || !fits(active, x + dx, y)) return false
        x += dx
        return true
    }

    fun rotate(): Boolean {
        if (gameOver) return false
        val height = active.cells.maxOf { it.y } + 1
        val rotated = active.copy(cells = active.cells.map { Cell(height - 1 - it.y, it.x) })
        for (offset in intArrayOf(0, -1, 1, -2, 2)) {
            if (fits(rotated, x + offset, y)) {
                x += offset
                active = rotated
                return true
            }
        }
        return false
    }

    val landingY: Int
        get() {
            var target = y
            while (fits(active, x, target + 1)) target++
            return target
        }

    /** Returns true when the piece locks. */
    fun step(softDrop: Boolean = false): Boolean {
        if (gameOver) return false
        if (fits(active, x, y + 1)) {
            y++
            if (softDrop) score++
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
        active = next
        next = takePiece()
        x = (COLUMNS - active.cells.maxOf { it.x } - 1) / 2
        y = 0
        gameOver = !fits(active, x, y)
    }

    private fun takePiece(): Piece {
        if (bag.isEmpty()) bag.addAll(shapes.indices.shuffled(random))
        val index = bag.removeFirst()
        val cells = shapes[index].flatMapIndexed { row, line ->
            line.mapIndexedNotNull { col, mark -> if (mark == '#') Cell(col, row) else null }
        }
        return Piece(index + 1, cells)
    }
}
