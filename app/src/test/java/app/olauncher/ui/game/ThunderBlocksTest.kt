package app.olauncher.ui.game

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunderBlocksTest {
    @Test
    fun `each bag contains the seven standard four-cell shapes`() {
        val game = ThunderBlocks(Random(18))
        val pieces = buildSet {
            repeat(7) {
                val cells = game.active.cells
                assertEquals(4, cells.size)
                add(cells.toSet())
                game.drop()
                game.board.fill(0)
            }
        }
        assertEquals(7, pieces.size)
    }

    @Test
    fun `rotation and running against a wall keep every cell in play`() {
        val game = ThunderBlocks(Random(5))
        repeat(ThunderBlocks.COLUMNS) { game.move(-1) }
        assertFalse(game.move(-1))
        repeat(4) {
            assertTrue(game.rotate())
            assertTrue(game.active.cells.all { game.x + it.x in 0 until ThunderBlocks.COLUMNS })
        }
        val landing = game.landingY
        val occupied = game.active.cells.map { (landing + it.y) * ThunderBlocks.COLUMNS + game.x + it.x }
        val next = game.next
        game.drop()
        assertTrue(occupied.all { game.board[it] != 0 })
        assertEquals(next, game.active)
        assertTrue(game.score > 0)
    }

    @Test
    fun `straight piece lifts three rows to rotate off the floor`() {
        val game = straightPiece()
        fallToGround(game)
        val originalX = game.x

        assertTrue(game.rotate())
        assertEquals(originalX, game.x)
        assertEquals(ThunderBlocks.ROWS - 4, game.y)
        assertEquals(4, game.active.cells.distinctBy { it.y }.size)
        assertEquals(ThunderBlocks.LOCK_DELAY, game.lockRemaining)
    }

    @Test
    fun `blocked rotation leaves the piece and pending lock intact`() {
        val game = straightPiece()
        fallToGround(game)
        val original = game.active
        val originalX = game.x
        val originalY = game.y
        for (row in originalY - 3 until originalY) {
            game.board.fill(1, row * ThunderBlocks.COLUMNS, (row + 1) * ThunderBlocks.COLUMNS)
        }
        assertFalse(game.elapseLockTime(100L))

        assertFalse(game.rotate())
        assertEquals(original, game.active)
        assertEquals(originalX, game.x)
        assertEquals(originalY, game.y)
        assertEquals(250L, game.lockRemaining)
    }

    @Test
    fun `wall kick prefers a sideways adjustment at the original height`() {
        val game = straightPiece()
        assertTrue(game.rotate())
        assertTrue(game.move(ThunderBlocks.COLUMNS - 2 - game.x))
        val originalY = game.y

        assertTrue(game.rotate())
        assertEquals(ThunderBlocks.COLUMNS - 4, game.x)
        assertEquals(originalY, game.y)
    }

    @Test
    fun `grounded piece locks once when its delay expires`() {
        val game = straightPiece()
        fallToGround(game)
        val next = game.next
        assertEquals(ThunderBlocks.LOCK_DELAY, game.lockRemaining)

        assertFalse(game.elapseLockTime(349L))
        assertFalse(game.step(softDrop = true))
        assertTrue(game.board.all { it == 0 })
        assertTrue(game.elapseLockTime(1L))
        assertEquals(next, game.active)
        assertEquals(4, game.board.count { it != 0 })
        assertFalse(game.elapseLockTime(350L))
        assertEquals(4, game.board.count { it != 0 })
    }

    @Test
    fun `successful grounded moves and rotations share a limited reset budget`() {
        val game = straightPiece()
        fallToGround(game)
        repeat(ThunderBlocks.MAX_LOCK_RESETS) { index ->
            assertFalse(game.elapseLockTime(100L))
            val adjusted = when (index) {
                0 -> game.rotate()
                else -> game.move(if (index % 2 == 0) -1 else 1)
            }
            assertTrue(adjusted)
            assertEquals(350L, game.lockRemaining)
        }

        val restored = restore(game.snapshot(elapsed = 100L))
        assertEquals(350L, game.lockRemaining)
        assertTrue(restored.move(-1))
        assertEquals(250L, restored.lockRemaining)
        assertTrue(restored.elapseLockTime(250L))
    }

    @Test
    fun `moving off a ledge lets gravity resume and retains spent lock time`() {
        val game = straightPiece()
        game.board[(ThunderBlocks.ROWS - 4) * ThunderBlocks.COLUMNS + game.x] = 1
        fallToGround(game)
        assertFalse(game.elapseLockTime(200L))

        assertTrue(game.move(1))
        assertNull(game.lockRemaining)
        assertFalse(game.elapseLockTime(350L))
        fallToGround(game)
        assertEquals(150L, game.lockRemaining)
    }

    @Test
    fun `hard drop bypasses the pending delay`() {
        val game = straightPiece()
        fallToGround(game)
        val next = game.next
        assertFalse(game.elapseLockTime(50L))

        game.drop()
        assertEquals(next, game.active)
        assertEquals(4, game.board.count { it != 0 })
        assertNull(game.lockRemaining)
        assertFalse(game.elapseLockTime(300L))
    }

    @Test
    fun `restored run preserves board rotation queue stats and remaining lock delay`() {
        val game = straightPiece()
        assertTrue(game.rotate())
        assertTrue(game.move(1))
        game.board[(ThunderBlocks.ROWS - 1) * ThunderBlocks.COLUMNS] = 2
        fallToGround(game)
        assertFalse(game.elapseLockTime(100L))
        val state = game.snapshot().copy(score = 1400L, lines = 8)
        val restored = restore(state)

        assertArrayEquals(state.board, restored.board)
        assertEquals(game.active, restored.active)
        assertEquals(game.next, restored.next)
        assertArrayEquals(state.bag, restored.snapshot().bag)
        assertEquals(game.x, restored.x)
        assertEquals(game.y, restored.y)
        assertEquals(1400L, restored.score)
        assertEquals(8, restored.lines)
        assertEquals(2, restored.level)
        assertEquals(250L, restored.lockRemaining)
        assertFalse(restored.elapseLockTime(249L))
        assertTrue(restored.elapseLockTime(1L))
        assertEquals(game.next, restored.active)
    }

    @Test
    fun `endless play advances past level thirty and keeps gravity bounded`() {
        val game = ThunderBlocks(Random(9))
        repeat(250) {
            game.board.fill(0)
            val bottom = ThunderBlocks.ROWS - 1
            val deepest = game.active.cells.maxOf { it.y }
            val holes = game.active.cells.filter { it.y == deepest }.map { game.x + it.x }.toSet()
            repeat(ThunderBlocks.COLUMNS) { column ->
                if (column !in holes) game.board[bottom * ThunderBlocks.COLUMNS + column] = 1
            }
            game.drop()
            assertEquals(1, game.clearedLastMove)
            assertFalse(game.clearedFourRows)
            assertEquals(1 shl bottom, game.clearedRows)
            assertFalse(game.gameOver)
        }
        assertEquals(250, game.lines)
        assertEquals(32, game.level)
        assertEquals(85L, game.interval)
    }

    @Test
    fun `four rows cleared by one piece qualify only for that lock`() {
        val game = ThunderBlocks(Random(18))
        while (game.active.cells.distinctBy { it.x }.size != 4) {
            game.drop()
            game.board.fill(0)
        }
        assertTrue(game.rotate())
        val firstRow = ThunderBlocks.ROWS - 4
        for (row in firstRow until ThunderBlocks.ROWS) {
            for (column in 0 until ThunderBlocks.COLUMNS) {
                if (column != game.x) game.board[row * ThunderBlocks.COLUMNS + column] = 1
            }
        }

        game.drop()
        assertEquals(4, game.lines)
        assertEquals(0b1111 shl firstRow, game.clearedRows)
        assertTrue(game.clearedFourRows)
        assertTrue(game.board.all { it == 0 })

        val restored = restore(game.snapshot())
        assertEquals(game.score, restored.score)
        assertEquals(4, restored.lines)
        assertEquals(0, restored.clearedRows)
        restored.drop()
        assertEquals(4, restored.lines)
        assertFalse(restored.clearedFourRows)
    }

    @Test
    fun `a blocked spawn ends the run without overwriting the stack`() {
        val game = ThunderBlocks(Random(11))
        val nextX = (ThunderBlocks.COLUMNS - game.next.cells.maxOf { it.x } - 1) / 2
        val blocker = game.next.cells.first()
        game.board[blocker.y * ThunderBlocks.COLUMNS + nextX + blocker.x] = 8
        game.drop()
        assertTrue(game.gameOver)
        val score = game.score
        game.drop()
        assertEquals(score, game.score)
    }
    private fun straightPiece(): ThunderBlocks {
        val state = ThunderBlocks(Random(18)).snapshot().copy(activeColor = 1)
        return ThunderBlocks(state = state)
    }

    private fun fallToGround(game: ThunderBlocks) {
        while (game.step()) Unit
    }

    private fun restore(state: ThunderBlocks.State): ThunderBlocks {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(state) }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            it.readObject() as ThunderBlocks.State
        }
        return ThunderBlocks(state = restored)
    }
}
