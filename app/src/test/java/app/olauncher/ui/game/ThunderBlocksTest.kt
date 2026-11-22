package app.olauncher.ui.game

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertEquals(1 shl bottom, game.clearedRows)
            assertFalse(game.gameOver)
        }
        assertEquals(250, game.lines)
        assertEquals(32, game.level)
        assertEquals(85L, game.interval)
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
}
