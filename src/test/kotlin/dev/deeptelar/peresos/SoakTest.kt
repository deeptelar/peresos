package dev.deeptelar.peresos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A long walk through the window: random actions, as a person and a terminal would do them over many
 * days, each one checked against what should have happened. It clicks the window's real buttons.
 *
 *     ./gradlew test                                 a short walk, with the other tests
 *     ./gradlew soak                                 3000 actions on a model that answers at once
 *     ./gradlew soak -Psteps=20000 -Pseed=7          longer, or again with the seed of a failure
 *     ./gradlew soak -Pgemma -Psteps=300             the real Gemma in Ollama: about a minute a chew
 *
 * The log of every action is in `build/soak/log.txt`.
 */
@OptIn(ExperimentalTestApi::class)
class SoakTest {
    private val steps = System.getProperty("soak.steps")?.toInt() ?: 80
    private val seed = System.getProperty("soak.seed")?.toLong() ?: System.nanoTime()
    private val gemma = System.getProperty("soak.gemma") == "true"
    private val random = Random(seed)

    private val home = Home(Files.createTempDirectory("peresos-soak"))
    private val client = if (gemma) HttpClient(CIO) else null
    private val kitchen = Kitchen(client?.let { Home.model(it) }, client?.let { Home.model(it, think = false) }, home::about)
    private val terminal = sloth(kitchen, home.pile, about = home::about)
    private val log = Path.of("build", "soak").createDirectories().resolve("log.txt").toFile().also { it.writeText("seed $seed, $steps steps, ${if (gemma) Home.modelName else "scripted model"}\n") }
    private val recent = ArrayDeque<String>()

    // What should be true, kept by the test as it acts.
    private var today = LocalDate.parse("2026-10-23")
    private val pile = mutableListOf<String>()
    private val wearing = mutableSetOf<Item>()
    private val facts = mutableListOf<String>()
    private var lastWake: LocalDate? = null
    private var yes = 0
    private var no = 0
    private var later = 0
    private var tries = 0
    private var mouth: String? = null
    private var stopped = false
    private var diaryOwed = 0

    private val counts = sortedMapOf<String, Int>()
    private val chewTimes = mutableListOf<Long>()
    private var hiccups = 0

    private lateinit var scope: CoroutineScope
    private var model: SlothModel by mutableStateOf(open())
    private var night by mutableStateOf(false)

    private fun open(): SlothModel {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val graph = sloth(kitchen, home.pile, about = home::about)
        val season = Season.entries.random(random).takeIf { random.nextBoolean() }
        val weather = Weather.entries.random(random).takeIf { random.nextBoolean() }
        return SlothModel(home, graph, scope, today = { today.toString() }, season = season, weather = weather)
    }

    @Test
    fun `a long walk through the window`() =
        runComposeUiTest(testTimeout = 24.hours) {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model, night) } }
            settle()
            val fds = mutableListOf<Int>()
            try {
                repeat(steps) { step ->
                    val (name, action) = pick()
                    val started = System.currentTimeMillis()
                    note("$step $name")
                    action()
                    settle()
                    check()
                    counts.merge(name, 1, Int::plus)
                    log.appendText("$step\t$name\t${System.currentTimeMillis() - started} ms\tpile ${pile.size}\tround ${yes + no}\n")
                    if (step % 100 == 0) fds += openFiles()
                }
            } catch (failure: Throwable) {
                val where = "window: ${model.session.snapshot.value}\ncheckpoint: ${runCatching { runBlocking { terminal.lastResult(home.config) } }.getOrNull()}\nbusy ${model.busy}, trouble ${model.trouble}"
                throw AssertionError("Failed with seed $seed after:\n${recent.joinToString("\n")}\n$where", failure)
            } finally {
                scope.cancel()
                client?.close()
            }
            fds += openFiles()
            report(fds)
            assertTrue(fds.last() - fds.first() < 40, "files left open: $fds")
        }

    /** Chooses the next action, more often the ones a person does every day. */
    private fun ComposeUiTest.pick(): Pair<String, () -> Unit> {
        val actions: List<Pair<Int, Pair<String, () -> Unit>>> =
            listOf(
                12 to ("feed" to { feed(items.random(random)) }),
                3 to ("feed blank" to { feed("   ") }),
                3 to ("feed odd" to { feed(oddItems.random(random)) }),
                12 to ("wake" to { wake() }),
                8 to ("something easy" to { wake(lazy = true) }),
                14 to ("take it" to { answer(Answer.YES) }),
                8 to ("bin it" to { answer(Answer.NO) }),
                6 to ("later" to { answer(Answer.LATER) }),
                5 to ("another try" to { answer(Answer.AGAIN) }),
                12 to ("next day" to { nextDay() }),
                5 to ("wardrobe" to { wear(Item.entries.random(random)) }),
                3 to ("tell a fact" to { learn() }),
                2 to ("diary tab" to { diaryTab() }),
                2 to ("town" to { town() }),
                4 to ("reopen the window" to { reopen() }),
                3 to ("terminal holds it" to { terminalHolds() }),
                2 to ("terminal feeds" to { terminalFeeds() }),
                2 to ("terminal wakes" to { terminalWakes() }),
                2 to ("edit the pile by hand" to { editByHand() }),
                2 to ("answer and feed at once" to { answerAndFeed() }),
            ) +
                // Ollama stopping halfway: only on the scripted model, so the real one is left alone.
                if (gemma) emptyList() else listOf(2 to ("ollama stops" to { kitchen.down = true }), 4 to ("ollama starts" to { kitchen.down = false }))
        var roll = random.nextInt(actions.sumOf { it.first })
        return actions.first { (weight, _) -> (roll < weight).also { roll -= weight } }.second
    }

    // The actions of the person, in the window.

    private fun ComposeUiTest.feed(text: String) {
        tab("Pile ${pile.size}")
        type(0, text, "Feed it")
        if (text.isNotBlank()) pile += text.trim()
    }

    /** Wake it, or Something easy when [lazy], or Try again after Ollama stopped it halfway. */
    private fun ComposeUiTest.wake(lazy: Boolean = false) {
        tab("Now")
        val button = if (stopped) "Try again" else if (lazy) "Something easy" else "Wake it"
        if (onAllNodesWithText(button).fetchSemanticsNodes().isEmpty()) return // it holds a bite: there is no button to wake it
        val canWake = stopped || (pile.isNotEmpty() && !ateToday())
        if (!canWake) return onNodeWithText(button).assertIsNotEnabled().let {}
        val resuming = stopped
        val lastLazy = state()?.lastLazy
        val picking = if (resuming) "pick" in pending() else lazy
        val started = System.currentTimeMillis()
        click(button)
        settle()
        if (!resuming) {
            lastWake?.let { assertEquals(ChronoUnit.DAYS.between(it, today).toInt(), state()?.sleptDays, "the days it slept") }
            lastWake = today
        }
        afterRun()
        when {
            picking && stopped -> mouth = null // Ollama stopped before it could pick
            picking -> mouth = picked(lastLazy)
            !resuming -> mouth = pile.first()
        }
        if (mouth != null && !stopped && diaryOwed == 0) chewTimes += System.currentTimeMillis() - started
    }

    /** The item a lazy wake picked: another than [lastLazy], and with the scripted model the shortest. */
    private fun picked(lastLazy: String?): String {
        val item = assertNotNull(state()?.item)
        val choices = pile.filter { it != lastLazy }.ifEmpty { pile }.take(30)
        assertTrue(item in choices, "it picked an item of the pile, and not the one of last time")
        if (!gemma) assertEquals(choices.minBy { it.replace("\n", " ").take(300).length }, item, "it picked the easiest")
        assertNotNull(state()?.why, "it says why")
        return item
    }

    private fun ComposeUiTest.answer(answer: Answer) {
        tab("Now")
        val button = buttons.getValue(answer)
        if (onAllNodesWithText(button).fetchSemanticsNodes().isEmpty()) return
        click(button)
        settle()
        swallowed(answer)
        afterRun()
    }

    /** What the sloth does to the pile when it swallows [answer], also when the diary fails after it. */
    private fun swallowed(answer: Answer) {
        val item = checkNotNull(mouth)
        when (answer) {
            Answer.YES -> yes++.also { pile.remove(item) }
            Answer.NO -> no++.also { pile.remove(item) }
            Answer.LATER -> later++.also { if (pile.remove(item)) pile += item }
            Answer.AGAIN -> tries++
        }
        if (answer != Answer.AGAIN) {
            mouth = null
            tries = 0
            diaryOwed = 1
        }
    }

    /** After a wake or an answer: did it finish, or stop because Ollama was gone? */
    private fun afterRun() {
        val snapshot = model.session.snapshot.value
        val halfway = snapshot is GraphSnapshot.Stopped || (snapshot is GraphSnapshot.Interrupted && SWALLOW !in snapshot.nextNodes)
        if (halfway && !kitchen.down) {
            if (!gemma) fail("It stopped without a reason: ${model.trouble}")
            hiccups++ // Gemma wrote something that is not a bite, twice: the person presses Try again
        }
        stopped = halfway
        if (!halfway && diaryOwed > 0 && pending().isEmpty()) diaryOwed = 0
        if (halfway && kitchen.down) assertTrue(model.trouble.orEmpty().contains("Is Ollama running?"), "it says why: ${model.trouble}")
    }

    /** Midnight passes with the window open: within a few seconds it may wake again. */
    private fun nextDay() {
        today = today.plusDays(random.nextLong(1, 4))
        val deadline = System.currentTimeMillis() + 10_000
        while (model.day != today.toString()) {
            if (System.currentTimeMillis() > deadline) fail("The window still thinks it is ${model.day}")
            Thread.sleep(20)
        }
    }

    private fun ComposeUiTest.wear(item: Item) {
        tab("Wear")
        click(item.name.lowercase())
        if (!wearing.remove(item)) wearing.removeAll { it.slot == item.slot }.also { wearing += item }
    }

    private fun ComposeUiTest.learn() {
        val fact = listOf("I work from home on Tuesdays", "My sister is called Núria", "I never answer email before ten", "I am learning Catalan").random(random)
        tab("You")
        type(0, fact, "Tell it")
        facts += fact
    }

    private fun ComposeUiTest.diaryTab() {
        tab("Diary")
        val last = state()?.diary?.lastOrNull() ?: return assertExists("The diary is empty. Peresós has not eaten anything yet.")
        assertExists(last.text)
    }

    /** No weather service in the test, so the window says it found no town. */
    private fun ComposeUiTest.town() {
        tab("You")
        type(1, "Girona", "Set")
        waitUntil(timeoutMillis = 5_000) { model.placeTrouble != null }
        waitForIdle()
        assertExists("Open-Meteo knows no town called Girona, or could not be reached.")
    }

    /** Closes the window and opens it again on the same home: it shows the same sloth. */
    private fun ComposeUiTest.reopen() {
        val before = model.session.snapshot.value.state
        scope.cancel()
        night = random.nextBoolean()
        model = open()
        settle()
        assertEquals(before, model.session.snapshot.value.state, "the window shows the sloth it showed when it closed")
    }

    // What a terminal does at the same time, on the same home.

    /** A terminal wakes it and waits for an answer, so the window must wait too. */
    private fun ComposeUiTest.terminalHolds() {
        val lock = assertNotNull(home.awake(), "the window let go of its lock")
        val before = model.session.snapshot.value
        lock.use {
            tab("Now")
            val button = listOf("Wake it", "Take it", "Try again").firstOrNull { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } ?: return
            if (button == "Wake it" && (pile.isEmpty() || ateToday())) return
            click(button)
            assertTrue(model.busy, "the window sees the terminal has it")
            assertExists("Peresós is awake in a terminal. Answer it there, then come back.")
            // The look every two seconds may read a stopped wake again from the checkpoint, as paused: the same sloth.
            val now = model.session.snapshot.value
            assertTrue(now !is GraphSnapshot.Running, "the window did nothing")
            assertEquals(before.state, now.state, "the window did nothing")
        }
        click("Look again")
        settle()
        assertTrue(!model.busy)
    }

    private fun ComposeUiTest.terminalFeeds() {
        val item = items.random(random)
        home.pile.add(item)
        pile += item
        synced()
    }

    /** `peresos` in a terminal: the same steps as Main, with a random answer for each bite. */
    private fun ComposeUiTest.terminalWakes() {
        if (kitchen.down) return
        val lock = assertNotNull(home.awake())
        lock.use {
            runBlocking {
                val config = home.config
                val last = terminal.lastResult(config)
                val woke = last == null || last is GraphResult.Completed
                var result =
                    when {
                        last == null -> terminal.invoke(Sloth(today.toString()), config)
                        last is GraphResult.Interrupted && SWALLOW in last.nextNodes -> last
                        last is GraphResult.Interrupted -> terminal.resume(config)
                        else -> terminal.invoke(last.state.copy(today = today.toString(), lazy = false), config)
                    }
                if (woke) {
                    lastWake?.let { assertEquals(ChronoUnit.DAYS.between(it, today).toInt(), result.state.sleptDays) }
                    lastWake = today
                    if (result is GraphResult.Interrupted) mouth = pile.first()
                }
                if (last is GraphResult.Interrupted && SWALLOW !in last.nextNodes) {
                    stopped = false
                    if (mouth == null && result is GraphResult.Interrupted) mouth = result.state.item // it picked, after Ollama came back
                }
                while (result is GraphResult.Interrupted && SWALLOW in result.nextNodes) {
                    val answer = (Answer.entries + null).random(random) ?: break // Enter: it keeps the bite for another day
                    result = terminal.resume(config) { it.copy(answer = answer) }
                    swallowed(answer)
                }
                if (result is GraphResult.Completed) diaryOwed = 0
            }
        }
        synced()
    }

    /** The person opens pile.md in an editor, tidies it and saves it. */
    private fun ComposeUiTest.editByHand() {
        if (random.nextInt(3) == 0 && pile.size > 1) pile.add(pile.removeAt(random.nextInt(pile.size)))
        val separator = listOf("\n---\n", "\n\n  ---  \n\n", "\n---\n\n").random(random)
        home.pile.file.writeText(pile.joinToString(separator, postfix = "\n"))
        synced()
    }

    /** The person feeds it in the same instant it swallows: neither may lose the other's change. */
    private fun ComposeUiTest.answerAndFeed() {
        tab("Now")
        if (onAllNodesWithText("Take it").fetchSemanticsNodes().isEmpty()) return feed(items.random(random))
        val item = "Call the plumber about the noise ${random.nextInt(1000)}"
        val answer = listOf(Answer.YES, Answer.NO).random(random) // a later would put the item after or before the new one
        click(buttons.getValue(answer))
        home.pile.add(item) // the terminal, at once
        pile += item
        settle()
        swallowed(answer)
        afterRun()
        synced()
    }

    // Checks.

    /** Everything the test knows against the files, the checkpoint and the screen. */
    private fun ComposeUiTest.check() {
        assertEquals(pile, home.pile.items(), "the pile on disk")
        assertEquals(pile, model.pile, "the pile in the window")
        assertExists("Pile ${pile.size}")
        assertEquals(wearing, home.wearing().toSet(), "what it wears")
        assertEquals(wearing, model.wearing.toSet())
        assertEquals(facts, home.about().lines().filter { it.isNotBlank() }.map { it.removePrefix("- ") })
        val dones = home.pile.done.takeIf { it.exists() }?.listDirectoryEntries()?.size ?: 0
        assertEquals(yes, dones, "a file in done for every yes")
        assertNotNull(home.awake(), "the lock is free between actions").close()

        tab("Now")
        val sloth = state() ?: return assertTrue(yes + no + later == 0)
        assertEquals(yes + no, sloth.roundness, "roundness")
        assertEquals(yes + no + later, sloth.meals.size, "meals")
        assertEquals(sloth.meals.size - diaryOwed, sloth.diary.size, "a line of the diary for every meal")
        assertEquals(tries, sloth.tries.size, "versions sent back")
        val snapshot = model.session.snapshot.value
        assertEquals(sloth, snapshot.state, "the window shows the checkpoint")
        assertExists(Portrait.caption(sloth.roundness, onTop = model.isFull(sloth)), Portrait.caption(sloth.roundness, grew = true, onTop = model.isFull(sloth)))
        when {
            stopped -> {
                assertTrue(snapshot is GraphSnapshot.Stopped || snapshot is GraphSnapshot.Interrupted)
                assertExists("Hmm.")
            }
            mouth != null -> {
                assertIs<GraphSnapshot.Interrupted<Sloth>>(snapshot)
                assertEquals(mouth, sloth.item)
                val bite = assertNotNull(sloth.bite)
                assertExists(bite.title)
                assertExists("Take it")
                sloth.why?.let { assertExists("It picked the easiest one: $it") }
                if (!gemma) assertTrue(bite.finished.startsWith("Done: ${mouth!!.lines().first()}"), "it chewed the item in its mouth")
                assertTrue(mouth!! in kitchen.lastChew, "Gemma was asked about the item in its mouth")
                kitchen.aboutThen.lines().filter { it.isNotBlank() }.forEach { assertTrue(it in kitchen.lastChew, "it told Gemma: $it") }
            }
            else -> {
                assertIs<GraphSnapshot.Completed<Sloth>>(snapshot)
                assertExists("Wake it")
                val wakeIt = onNodeWithText("Wake it")
                val easy = onNodeWithText("Something easy")
                if (pile.isNotEmpty() && !ateToday()) {
                    wakeIt.assertIsEnabled()
                    easy.assertIsEnabled()
                } else {
                    wakeIt.assertIsNotEnabled()
                    easy.assertIsNotEnabled()
                }
                val meal = sloth.meals.lastOrNull()
                val title =
                    when {
                        sloth.lastWake != today.toString() || sloth.rest != Rest.NONE || meal == null -> "Zzz..."
                        meal.answer == Answer.YES -> "Gulp! Swallowed."
                        meal.answer == Answer.NO -> "Ptooey! Spat out."
                        else -> "Back on the pile."
                    }
                assertExists(title)
            }
        }
    }

    private fun ateToday() = state()?.lastMeal == today.toString()

    private fun state(): Sloth? = runBlocking { terminal.lastResult(home.config)?.state }

    /** The nodes the last run stopped before: none when it finished. */
    private fun pending(): List<String> = runBlocking { (terminal.lastResult(home.config) as? GraphResult.Interrupted)?.nextNodes.orEmpty() }

    // Driving the window.

    /** Waits until no wake runs and the window holds no lock, then until the screen is drawn. */
    private fun ComposeUiTest.settle() {
        val deadline = System.currentTimeMillis() + if (gemma) 20 * 60_000 else 30_000
        while (true) {
            val snapshot = model.session.snapshot.value
            val free = home.awake()?.also { it.close() } != null
            if (free && snapshot !is GraphSnapshot.Restoring && snapshot !is GraphSnapshot.Running) break
            if (System.currentTimeMillis() > deadline) fail("Still busy: $snapshot")
            Thread.sleep(5)
        }
        waitForIdle()
    }

    /** Waits for the window to notice what the terminal changed: it looks every two seconds. */
    private fun ComposeUiTest.synced() {
        val deadline = System.currentTimeMillis() + 10_000
        while (model.pile != pile || model.session.snapshot.value.state != state()) {
            if (System.currentTimeMillis() > deadline) fail("The window never noticed: it shows ${model.pile}, the file has $pile")
            Thread.sleep(50)
        }
        settle()
    }

    private fun ComposeUiTest.tab(title: String) = click(title)

    /** Clicks the button or tab with [text], which the person must see without scrolling. */
    private fun ComposeUiTest.click(text: String) {
        onAllNodesWithText(text)[0].assertIsDisplayed().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.type(
        field: Int,
        text: String,
        button: String,
    ) {
        val node = onAllNodes(hasSetTextAction())[field]
        node.performTextInput(text)
        waitForIdle()
        if (text.isBlank()) {
            onNodeWithText(button).assertIsNotEnabled()
            node.performTextClearance()
        } else {
            click(button)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertExists(vararg texts: String) {
        if (texts.none { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }) fail("Not on the screen: ${texts.joinToString(" or ")}")
    }

    private fun note(line: String) {
        recent.addLast(line)
        if (recent.size > 40) recent.removeFirst()
    }

    private fun openFiles(): Int = File("/proc/self/fd").list()?.size ?: 0

    private fun report(fds: List<Int>) {
        val checkpoint = home.path.resolve("checkpoints/peresos.json")
        val chews = chewTimes.chunked(maxOf(1, (chewTimes.size + 3) / 4)).map { it.average().toLong() }
        val summary =
            """
            |Soak: $steps actions, seed $seed, ${if (gemma) Home.modelName else "scripted model"}, ${ChronoUnit.DAYS.between(LocalDate.parse("2026-10-23"), today)} days
            |  ${counts.entries.joinToString { "${it.key} ${it.value}" }}
            |  ate ${yes + no + later} (took $yes, binned $no, later $later), ${pile.size} left on the pile
            |  wake to bite, by quarter of the walk: ${chews.joinToString(" → ") { "$it ms" }}
            |  checkpoint ${checkpoint.takeIf { it.exists() }?.fileSize()?.div(1024) ?: 0} KB, open files $fds, heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB
            |  ${if (gemma) "Gemma hiccups (Try again): $hiccups" else ""}
            """.trimMargin()
        println(summary)
        log.appendText(summary + "\n")
    }

    private companion object {
        val buttons = mapOf(Answer.YES to "Take it", Answer.NO to "Bin it", Answer.LATER to "Later", Answer.AGAIN to "Another try")

        val items =
            listOf(
                "From my landlord: the yearly boiler check is due. Which morning suits you?",
                "Decide on the gym membership",
                "Reply to Marta about dinner on Friday",
                "Idea: a newsletter about slow cooking",
                "Book the car's MOT",
                "La Núria pregunta si podem quedar dissabte per dinar",
                "Answer the recruiter from Acme\nThey want a call this week.\nI am not looking, but I like them.",
                "Cancel the magazine I never read",
                "Write down the wifi password for guests",
                "Decide on the gym membership", // twice on the pile, as happens
            )

        /** Things a person might paste that a plain-text pile must survive. */
        val oddItems =
            listOf(
                "🦥 emoji and ünïcödé, and «quotes»",
                "  leading and trailing spaces   ",
                "A very long one: " + "words that go on and on ".repeat(60),
                "Tabs\tinside\tit",
                "Markdown: **bold**, a [link](https://example.com) and `code`",
            )
    }
}

/**
 * Chews like Gemma, but at once: the bite names the item, so the test can tell which one it
 * chewed. With [chef] and [diarist] it asks the real models instead. [down] is Ollama stopped.
 */
private class Kitchen(
    private val chef: ChatModel?,
    private val diarist: ChatModel?,
    private val about: () -> String,
) : ChatModel {
    @Volatile var down = false

    /** The last prompt it chewed with. */
    @Volatile var lastChew = ""

    /** What the person had told it about themselves when it last chewed. */
    @Volatile var aboutThen = ""

    override suspend fun chat(request: ChatRequest): ChatResponse {
        if (down) throw ConnectException("Connection refused")
        val prompt = request.messages.last().text
        val chewing = "Make it done for me" in prompt
        if ("Which item is the easiest" in prompt) {
            chef?.let { return it.chat(request) }
            // A lazy sloth picks the shortest.
            val items = prompt.lines().mapNotNull { Regex("^(\\d+)\\. (.*)$").find(it)?.groupValues }
            val shortest = items.minBy { it[2].length }[1].toInt()
            return ChatResponse(ChatMessage.Assistant(Json.encodeToString(Pick.serializer(), Pick(shortest, "It is the shortest."))))
        }
        if (chewing) {
            lastChew = prompt
            aboutThen = about()
        }
        (if (chewing) chef else diarist)?.let { return it.chat(request) }
        delay(ThreadLocalRandom.current().nextLong(0, 40))
        if (!chewing) return ChatResponse(ChatMessage.Assistant("Ate one, slowly. Nobody hurried."))
        val item = prompt.substringAfter("Make it done for me.\n\n").substringBefore("\n\nI did not want")
        val tried = prompt.substringAfter("I did not want", "").lines().count { it.startsWith("- ") }
        val bite = Bite(Kind.entries[Math.floorMod(item.hashCode(), Kind.entries.size)], "Ate: ${item.lines().first().take(24).trim()} #${tried + 1}", "Done: $item (try ${tried + 1})")
        return ChatResponse(ChatMessage.Assistant(Json.encodeToString(Bite.serializer(), bite)))
    }
}
