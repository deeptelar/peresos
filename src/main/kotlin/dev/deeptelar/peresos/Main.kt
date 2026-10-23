package dev.deeptelar.peresos

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.openai.OpenAiChatModel
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import java.nio.file.Path as JavaPath

private val home: String = System.getenv("PERESOS_HOME") ?: "${System.getProperty("user.home")}/.peresos"
private val modelName: String = System.getenv("PERESOS_MODEL") ?: "gemma4:12b"
private val aboutFile: JavaPath = JavaPath.of(home, "about.md")

/** The home folder as the person would type it. */
private val shownHome: String = home.replaceFirst(System.getProperty("user.home"), "~")

suspend fun main(args: Array<String>) {
    val pile = FilePile(JavaPath.of(home, "pile.md"), JavaPath.of(home, "done"))
    val config =
        GraphConfig(
            threadId = "peresos",
            checkpointer = FileCheckpointer(Path(home, "checkpoints"), KotlinxStateSerializer<Sloth>()),
            interruptBefore = setOf(SWALLOW),
        )
    when (args.firstOrNull()) {
        "feed" -> feed(pile, args.drop(1))
        "about" -> about(args.drop(1))
        "pile" -> showPile(pile)
        "diary" -> showDiary(sloth(Unused, pile).lastResult(config)?.state)
        null, "--hungry" -> HttpClient(CIO).use { client -> wake(sloth(model(client), pile, hungry = "--hungry" in args, diarist = model(client, think = false), about = aboutFile.readTextOrEmpty()), config) }
        else -> println(USAGE)
    }
}

/** For the commands that only read the checkpoint and never call a model. */
private object Unused : ChatModel {
    override suspend fun chat(request: ChatRequest): ChatResponse = error("This command does not call a model.")
}

private fun model(client: HttpClient, think: Boolean = true) =
    OpenAiChatModel.ollama(
        client,
        model = modelName,
        // Gemma 4 thinks before it answers. Worth it for the finished version, not for a line of the diary.
        parameters = if (think) JsonObject(emptyMap()) else buildJsonObject { put("reasoning_effort", "none") },
        timeout = 15.minutes,
    )

private suspend fun wake(graph: CompiledGraph<Sloth>, config: GraphConfig<Sloth>) {
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
        Rest.EMPTY_PILE -> say("The pile is empty. Nothing to eat, so it goes back to sleep. Feed it with: peresos feed \"...\"")
        Rest.NONE -> {
            val meal = sloth.meals.last()
            when (meal.answer) {
                Answer.YES -> say("Swallowed. The finished version is in $shownHome/done.")
                Answer.NO -> say("Spat out. One thing fewer to think about.")
                Answer.LATER -> say("Put back at the bottom of the pile.")
                Answer.AGAIN -> Unit // never a meal: the item went back to be chewed
            }
            sloth.diary.lastOrNull()?.let { entry -> wrap("Diary: \"${entry.text}\"", 84).forEachIndexed { index, line -> say(if (index == 0) line else "       $line") } }
        }
    }
    println()
    val grew = sloth.rest == Rest.NONE && Portrait.phase(sloth.roundness) != Portrait.phase(sloth.roundness - 1)
    showPortrait(sloth.roundness, grew)
}

/** Whether a person watches the output in a terminal, so the portrait can move. Not when it goes to a file or a pipe. */
private val animated: Boolean =
    System.getenv("TERM") != "dumb" &&
        System.console()?.let { console -> runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true) } == true

/** The portrait and the phase it is in. In a terminal it plays its animation twice, slowly, then stands still. */
private suspend fun showPortrait(
    roundness: Int,
    grew: Boolean = false,
) {
    val frames = Portrait.frames(roundness)
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
    val finished = "$roundness ${if (roundness == 1) "item" else "items"} finished"
    say("${if (grew) "It grew! Peresós is now" else "Peresós is"} ${Portrait.phase(roundness).title} ($finished).")
}

private fun feed(pile: Pile, words: List<String>) {
    val item = words.joinToString(" ").ifBlank { generateSequence(::readLine).joinToString("\n") }
    if (item.isBlank()) return println(USAGE)
    pile.add(item)
    val size = pile.items().size
    say("Peresós looks at the pile: $size ${if (size == 1) "item" else "items"}. It will get to it. Eventually.")
}

private fun about(words: List<String>) {
    val fact = words.joinToString(" ").trim()
    if (fact.isNotEmpty()) {
        aboutFile.parent.createDirectories()
        aboutFile.writeText(aboutFile.readTextOrEmpty() + "- $fact\n")
    }
    val facts = aboutFile.readTextOrEmpty()
    if (facts.isBlank()) return say("It knows nothing about you yet. Tell it: peresos about \"I work from home on Tuesdays\"")
    say("What Peresós knows about you, and uses when it prepares things ($shownHome/about.md):")
    facts.lines().filter { it.isNotBlank() }.forEach { println("    $it") }
}

private fun JavaPath.readTextOrEmpty(): String = if (exists()) readText() else ""

private fun showPile(pile: Pile) {
    val items = pile.items()
    if (items.isEmpty()) return say("The pile is empty.")
    items.forEachIndexed { index, item -> println("  ${index + 1}. ${item.lines().first().take(70)}") }
}

private suspend fun showDiary(sloth: Sloth?) {
    if (sloth == null || sloth.diary.isEmpty()) return say("The diary is empty. Peresós has not eaten anything yet.")
    sloth.diary.forEach { entry -> wrap(entry.text, 70).forEachIndexed { index, line -> println("  ${if (index == 0) entry.day else " ".repeat(10)}  $line") } }
    println()
    showPortrait(sloth.roundness)
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

    It runs on $modelName in your local Ollama (PERESOS_MODEL changes it). Nothing leaves your computer.
    """.trimIndent()
