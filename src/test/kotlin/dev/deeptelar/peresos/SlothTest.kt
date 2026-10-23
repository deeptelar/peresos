package dev.deeptelar.peresos

import dev.deeptelar.peresos.Portrait.Phase.BABY
import dev.deeptelar.peresos.Portrait.Phase.GROWN
import dev.deeptelar.peresos.Portrait.Phase.ROUND
import dev.deeptelar.peresos.Portrait.Phase.YOUNG
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.test.ScriptedChatModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlothTest {
    private class MemoryPile(vararg items: String) : Pile {
        val items = items.toMutableList()
        val kept = mutableListOf<Bite>()

        override fun items(): List<String> = items.toList()

        override fun add(item: String) {
            items += item
        }

        override fun remove(item: String) {
            items.remove(item)
        }

        override fun moveToEnd(item: String) {
            items.remove(item)
            items += item
        }

        override fun keep(day: String, bite: Bite) {
            kept += bite
        }
    }

    private val reply = Bite(Kind.REPLY, "Answer Marta about Friday", "Hi Marta, Friday works for me. See you at eight!")
    private fun config() = GraphConfig(threadId = "test", checkpointer = MemoryCheckpointer<Sloth>(), interruptBefore = setOf(SWALLOW))

    @Test
    fun `it chews the oldest item and waits for the answer before it swallows`() =
        runTest {
            val pile = MemoryPile("Marta asked if Friday dinner still works", "Decide on the gym")
            val model = ScriptedChatModel { answers(reply) }
            val config = config()

            val result = sloth(model, pile).invoke(Sloth("2026-10-23"), config)

            assertIs<GraphResult.Interrupted<Sloth>>(result)
            assertEquals(listOf(SWALLOW), result.nextNodes)
            assertEquals(reply, result.state.bite)
            assertEquals(2, pile.items.size, "nothing leaves the pile before the person answers")
        }

    @Test
    fun `a yes keeps the finished version, empties the item and writes the diary`() =
        runTest {
            val pile = MemoryPile("Marta asked if Friday dinner still works")
            val model = ScriptedChatModel {
                answers(reply)
                says("Ate a dinner plan. Delicious, and nobody had to hurry.")
            }
            val config = config()
            val graph = sloth(model, pile)
            graph.invoke(Sloth("2026-10-23"), config)

            val state = graph.resume(config) { it.copy(answer = Answer.YES) }.state

            assertEquals(listOf(reply), pile.kept)
            assertTrue(pile.items.isEmpty())
            assertEquals(1, state.roundness)
            assertEquals("2026-10-23", state.lastMeal)
            assertEquals(DiaryEntry("2026-10-23", "Ate a dinner plan. Delicious, and nobody had to hurry."), state.diary.single())
            assertTrue(state.emptied, "that was the last item of the pile")
            assertTrue("the pile is empty now" in model.requests.last().messages.toString())
            assertNull(state.item)
        }

    @Test
    fun `later puts the item back at the bottom, and it does not count`() =
        runTest {
            val pile = MemoryPile("Marta asked if Friday dinner still works", "Decide on the gym")
            val model = ScriptedChatModel {
                answers(reply)
                says("Spat it back out. Tomorrow is also a day.")
            }
            val config = config()
            val graph = sloth(model, pile)
            graph.invoke(Sloth("2026-10-23"), config)

            val state = graph.resume(config) { it.copy(answer = Answer.LATER) }.state

            assertEquals(listOf("Decide on the gym", "Marta asked if Friday dinner still works"), pile.items)
            assertEquals(0, state.roundness)
            assertFalse(state.emptied, "the pile still has both items")
        }

    @Test
    fun `one item a day, and it knows how long it slept`() =
        runTest {
            val pile = MemoryPile("Marta asked if Friday dinner still works", "Decide on the gym")
            val model = ScriptedChatModel {
                answers(reply)
                says("Ate one. That is enough for a day.")
                answers(Bite(Kind.DECISION, "The gym", "Go on Tuesdays only. One day a week is a habit you keep."))
            }
            val config = config()
            val graph = sloth(model, pile)
            graph.invoke(Sloth("2026-10-23"), config)
            val fed = graph.resume(config) { it.copy(answer = Answer.YES) }.state

            val sameDay = graph.invoke(fed, config)
            assertIs<GraphResult.Completed<Sloth>>(sameDay)
            assertEquals(Rest.ALREADY_ATE, sameDay.state.rest)

            val fourDaysLater = graph.invoke(sameDay.state.copy(today = "2026-10-27"), config)
            assertIs<GraphResult.Interrupted<Sloth>>(fourDaysLater)
            assertEquals(4, fourDaysLater.state.sleptDays)
            assertEquals("The gym", fourDaysLater.state.bite?.title)
        }

    @Test
    fun `another try chews the item again and tells the model what was not wanted`() =
        runTest {
            val pile = MemoryPile("Decide on the gym")
            val model = ScriptedChatModel {
                answers(Bite(Kind.DECISION, "The gym", "Cancel it."))
                answers(Bite(Kind.DECISION, "The gym", "Keep it, and go on Tuesdays."))
                says("Chewed twice, swallowed once.")
            }
            val config = config()
            val graph = sloth(model, pile)
            graph.invoke(Sloth("2026-10-23"), config)

            val again = graph.resume(config) { it.copy(answer = Answer.AGAIN) }
            assertIs<GraphResult.Interrupted<Sloth>>(again)
            assertEquals("Keep it, and go on Tuesdays.", again.state.bite?.finished)
            assertTrue("Cancel it." in model.requests[1].messages.last().text, "the second try knows the first one was not wanted")
            assertEquals(listOf("Decide on the gym"), pile.items, "nothing was swallowed")

            val state = graph.resume(config) { it.copy(answer = Answer.YES) }.state
            assertEquals(1, state.roundness)
            assertTrue(state.tries.isEmpty())
        }

    @Test
    fun `what the person told it about themselves goes into the prompt`() =
        runTest {
            val model = ScriptedChatModel { answers(reply) }

            sloth(model, MemoryPile("The landlord needs a morning for the boiler check"), about = "- I work from home on Tuesdays")
                .invoke(Sloth("2026-10-23"), config())

            assertTrue("I work from home on Tuesdays" in model.requests.single().messages.last().text)
        }

    @Test
    fun `an empty pile means more sleep`() =
        runTest {
            val result = sloth(ScriptedChatModel { }, MemoryPile()).invoke(Sloth("2026-10-23"), config())

            assertIs<GraphResult.Completed<Sloth>>(result)
            assertEquals(Rest.EMPTY_PILE, result.state.rest)
        }

    @Test
    fun `the portrait grows from a baby into a big round sloth`() {
        val phases = listOf(0, 1, 2, 4, 5, 9, 10, 40).map { Portrait.phase(it) }
        val sizes = Portrait.Phase.entries.map { phase -> Portrait.frames(listOf(0, 2, 5, 10)[phase.ordinal]).first().size }

        assertEquals(listOf(BABY, BABY, YOUNG, YOUNG, GROWN, GROWN, ROUND, ROUND), phases)
        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes, sizes.distinct())
    }

    @Test
    fun `every item shows, in every phase and pose`() {
        for (roundness in listOf(0, 2, 5, 10)) for (onTop in listOf(false, true)) {
            val bare = Portrait.lines(roundness, onTop)
            for (item in Item.entries) assertTrue(Portrait.lines(roundness, onTop, listOf(item)) != bare, "$item at $roundness, on top: $onTop")
        }
    }

    @Test
    fun `every frame of an animation has the same size, so the next one draws over it`() {
        val outfits = listOf(emptyList<Item>(), Item.entries) + Item.entries.map { listOf(it) }
        for (roundness in listOf(0, 2, 5, 10)) for (onTop in listOf(false, true)) for (outfit in outfits) {
            val frames = Portrait.frames(roundness, onTop, outfit)
            val widths = frames.flatMap { frame -> frame.map { Regex("\u001B\\[[0-9;]*m").replace(it, "").length } }

            assertTrue(frames.size > 1)
            assertEquals(1, frames.map { it.size }.distinct().size)
            assertEquals(1, widths.distinct().size)
        }
    }
}
