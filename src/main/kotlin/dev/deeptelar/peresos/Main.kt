package dev.deeptelar.peresos

import dev.deeptelar.peresos.Home.Companion.model
import dev.deeptelar.peresos.Home.Companion.modelName
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

private val home = Home.default

suspend fun main(args: Array<String>) {
    val pile = home.pile
    when (args.firstOrNull()) {
        "feed" -> feed(pile, args.drop(1))
        "about" -> about(args.drop(1))
        "pile" -> showPile(pile)
        "wear" -> wear(args.drop(1), sloth(Unused, pile).lastResult(home.config)?.state)
        "diary" -> showDiary(sloth(Unused, pile).lastResult(home.config)?.state)
        "place" -> HttpClient(CIO).use { client -> place(OpenMeteo(client), args.drop(1)) }
        "window" -> window(home, hungry = "--hungry" in args, season = option(args, "season", Season.entries), weather = option(args, "weather", Weather.entries))
        null, "--hungry" -> {
            val awake = home.awake() ?: return say("Peresós is awake in its window, or in another terminal. Answer it there.")
            awake.use { HttpClient(CIO).use { client -> wake(sloth(model(client), pile, hungry = "--hungry" in args, diarist = model(client, think = false), about = home::about)) } }
        }
        else -> println(USAGE)
    }
}

/** For the commands that only read the checkpoint and never call a model. */
internal object Unused : ChatModel {
    override suspend fun chat(request: ChatRequest): ChatResponse = error("This command does not call a model.")
}

private suspend fun wake(graph: CompiledGraph<Sloth>) {
    val config = home.config
    val today = LocalDate.now().toString()
    var result =
        when (val last = graph.lastResult(config)) {
            null -> chewing { graph.invoke(Sloth(today), config) }
            is GraphResult.Interrupted if SWALLOW in last.nextNodes -> last.also { say("Peresós still has this in its mouth, from ${it.state.today}.") }
            is GraphResult.Interrupted -> chewing { graph.resume(config) } // the last wake stopped halfway, say Ollama was not running
            is GraphResult.Completed -> chewing { graph.invoke(last.state.copy(today = today), config) }
        }

    while (result is GraphResult.Interrupted) {
        val answer = askFor(result.state) ?: return say("Peresós holds it in its mouth until you are ready. Run peresos again to answer.")
        if (answer == Answer.AGAIN) say("Peresós chews it again. Slowly.")
        result = graph.resume(config) { it.copy(answer = answer) }
    }
    report(result.state)
}

private suspend fun <T> chewing(run: suspend () -> T): T {
    say("Peresós wakes up and looks at the pile. Slowly.")
    return run()
}

private fun askFor(sloth: Sloth): Answer? {
    val bite = checkNotNull(sloth.bite)
    println()
    say("It chewed this one: ${bite.title}  (${bite.kind.name.lowercase()})")
    println()
    wrap(bite.finished).forEach { println("    $it") }
    println()
    print("  [y] take it   [n] bin it   [l] later   [a] another try   (Enter: decide another day)  ")
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes" -> Answer.YES
        "n", "no" -> Answer.NO
        "l", "later" -> Answer.LATER
        "a", "again" -> Answer.AGAIN
        else -> null
    }
}

private suspend fun report(sloth: Sloth) {
    println()
    if (sloth.sleptDays > 1) say("It slept ${sloth.sleptDays} days. It feels wonderful.")
    when (sloth.rest) {
        Rest.ALREADY_ATE -> say("It already ate today. One item a day is plenty. Come back tomorrow.")
        Rest.EMPTY_PILE if sloth.roundness > 0 -> say("The pile is still empty. Peresós sleeps on top of its branch until you feed it: peresos feed \"...\"")
        Rest.EMPTY_PILE -> say("The pile is empty. Nothing to eat, so it goes back to sleep. Feed it with: peresos feed \"...\"")
        Rest.NONE -> {
            val meal = sloth.meals.last()
            when (meal.answer) {
                Answer.YES -> say("Swallowed. The finished version is in ${home.shown}/done.")
                Answer.NO -> say("Spat out. One thing fewer to think about.")
                Answer.LATER -> say("Put back at the bottom of the pile.")
                Answer.AGAIN -> Unit // never a meal: the item went back to be chewed
            }
            if (sloth.emptied) say("That was the last one. The pile is empty: Peresós ate everything. Nothing left to put off.")
            sloth.diary.lastOrNull()?.let { entry -> wrap("Diary: \"${entry.text}\"", 84).forEachIndexed { index, line -> say(if (index == 0) line else "       $line") } }
        }
    }
    println()
    val grew = sloth.rest == Rest.NONE && Portrait.phase(sloth.roundness) != Portrait.phase(sloth.roundness - 1)
    showPortrait(sloth.roundness, grew, onTop = home.isFull(sloth))
}

/** Whether a person watches the output in a terminal, so the portrait can move. Not when it goes to a file or a pipe. */
private val animated: Boolean =
    System.getenv("TERM") != "dumb" &&
        System.console()?.let { console -> runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true) } == true

/** The portrait and the phase it is in. In a terminal it plays its animation twice, slowly, then stands still. */
private suspend fun showPortrait(
    roundness: Int,
    grew: Boolean = false,
    onTop: Boolean = false,
) {
    val frames = Portrait.frames(roundness, onTop, home.wearing())
    if (animated) {
        repeat(2) {
            frames.forEach { frame ->
                frame.forEach { println("  $it") }
                delay(200.milliseconds)
                print("\u001B[${frame.size}A")
            }
        }
    }
    frames.first().forEach { println("  $it") }
    println()
    say(Portrait.caption(roundness, grew, onTop))
}

private fun feed(pile: Pile, words: List<String>) {
    val item = words.joinToString(" ").ifBlank { generateSequence(::readLine).joinToString("\n") }
    if (item.isBlank()) return println(USAGE)
    pile.add(item)
    val size = pile.items().size
    say("Peresós looks at the pile: $size ${if (size == 1) "item" else "items"}. It will get to it. Eventually.")
}

private fun about(words: List<String>) {
    home.learn(words.joinToString(" "))
    val facts = home.about()
    if (facts.isBlank()) return say("It knows nothing about you yet. Tell it: peresos about \"I work from home on Tuesdays\"")
    say("What Peresós knows about you, and uses when it prepares things (${home.shown}/about.md):")
    facts.lines().filter { it.isNotBlank() }.forEach { println("    $it") }
}

private suspend fun wear(
    words: List<String>,
    sloth: Sloth?,
) {
    val names = words.map { it.lowercase() }
    if (names == listOf("nothing")) {
        home.dress(emptyList())
    } else if (names.isNotEmpty()) {
        val asked = names.map { name -> Item.entries.find { it.name.lowercase() == name } ?: return say("Peresós has no $name. It can wear: ${Item.entries.joinToString { it.name.lowercase() }}.") }
        home.putOn(asked)
    } else {
        say("What Peresós can wear (peresos wear sunglasses cap, or peresos wear nothing):")
        Item.entries.forEach { println("    %-11s %s".format(it.name.lowercase(), it.description)) }
        println()
    }
    val on = home.wearing()
    say(if (on.isEmpty()) "It wears nothing but its fur." else "It wears: ${on.joinToString { it.name.lowercase() }}.")
    println()
    showPortrait(sloth?.roundness ?: 0, onTop = home.isFull(sloth))
}

private fun showPile(pile: Pile) {
    val items = pile.items()
    if (items.isEmpty()) return say("The pile is empty.")
    items.forEachIndexed { index, item -> println("  ${index + 1}. ${item.lines().first().take(70)}") }
}

private suspend fun showDiary(sloth: Sloth?) {
    if (sloth == null || sloth.diary.isEmpty()) return say("The diary is empty. Peresós has not eaten anything yet.")
    sloth.diary.forEach { entry -> wrap(entry.text, 70).forEachIndexed { index, line -> println("  ${if (index == 0) entry.day else " ".repeat(10)}  $line") } }
    println()
    showPortrait(sloth.roundness, onTop = home.isFull(sloth))
}

/** The value of `--name=value` in [args], for trying another season or weather in the window. */
private fun <T : Enum<T>> option(
    args: Array<String>,
    name: String,
    values: List<T>,
): T? = args.firstNotNullOfOrNull { it.removePrefix("--$name=").takeIf { value -> value != it } }?.let { value -> values.find { it.name.equals(value, ignoreCase = true) } }

private suspend fun place(
    meteo: OpenMeteo,
    words: List<String>,
) {
    val name = words.joinToString(" ").trim()
    when {
        name == "nothing" -> home.settle(null)
        name.isNotEmpty() -> home.settle(meteo.find(name) ?: return say("Open-Meteo knows no town called $name."))
    }
    val place = home.place() ?: return say("The window shows the season only. For the weather too: peresos place \"your town\"")
    say("The window shows the weather in ${place.name}, from Open-Meteo. Only the town is sent, never the pile.")
}

private fun say(text: String) = println("  $text")

private fun wrap(text: String, width: Int = 76): List<String> =
    text.trim().lines().flatMap { line ->
        if (line.isBlank()) {
            listOf("")
        } else {
            line.split(" ").fold(mutableListOf("")) { lines, word ->
                if (lines.last().isNotEmpty() && lines.last().length + word.length + 1 > width) lines.add(word) else lines[lines.lastIndex] = (lines.last() + " " + word).trim()
                lines
            }
        }
    }

private val USAGE =
    """
    Peresós eats one item of your backlog a day, and you only say yes or no.

      peresos feed "text"     put something you avoid on the pile (or pipe text into it)
      peresos about "fact"    tell it something about you that helps, like when you are home
      peresos                 wake it: it eats the oldest item and shows you the finished version
      peresos pile            what is still on the pile
      peresos diary           what it wrote in its diary
      peresos wear sunglasses dress it up: sunglasses, cap, barretina, coffee (peresos wear nothing to undress)
      peresos window          open its home in a window, where you can do all of this with buttons
      peresos place "town"    show that town's weather in the window, from Open-Meteo (peresos place nothing to stop)

    It runs on $modelName in your local Ollama (PERESOS_MODEL changes it). Nothing leaves your computer,
    except the town you give peresos place.
    """.trimIndent()
