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
import java.awt.Desktop
import java.net.ConnectException
import java.net.URI
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private val home = Home.default

private val MAC = System.getProperty("os.name").startsWith("Mac")

suspend fun main(args: Array<String>) {
    // The window's threads have no terminal to print to: what fails there goes to the log.
    Thread.setDefaultUncaughtExceptionHandler { thread, e -> home.log.error("Failed in ${thread.name}", e) }
    try {
        run(args)
    } catch (e: Throwable) {
        home.log.error("peresos ${args.joinToString(" ")}", e)
        say(trouble(e))
        say("What happened is in ${home.shown}/logs/peresos.log. To report it: peresos doctor --report")
        exitProcess(1)
    }
}

private suspend fun run(args: Array<String>) {
    val pile = home.pile
    when (args.firstOrNull()) {
        "feed" -> feed(pile, args.drop(1))
        "about" -> about(args.drop(1))
        "pile" -> showPile(pile)
        "wear" -> wear(args.drop(1), sloth(Unused, pile).lastResult(home.config)?.state)
        "diary" -> showDiary(sloth(Unused, pile).lastResult(home.config)?.state)
        "place" -> HttpClient(CIO).use { client -> place(OpenMeteo(client), args.drop(1)) }
        "doctor" -> doctor(fix = "--fix" in args, report = "--report" in args)
        "uninstall" -> uninstall()
        "--version", "version" -> println("peresos $VERSION")
        "window" -> window(home, hungry = "--hungry" in args, season = option(args, "season", Season.entries), weather = option(args, "weather", Weather.entries))
        // Opened from the Finder, the app of macOS has no terminal: it opens the window.
        null if MAC && System.getenv("TERM") == null && System.console() == null -> window(home)
        null, "--hungry", "--lazy" -> {
            val awake = home.awake() ?: return say("Peresós is awake in its window, or in another terminal. Answer it there.")
            awake.use {
                HttpClient(CIO).use { client ->
                    wake(sloth(model(client), pile, hungry = "--hungry" in args, diarist = model(client, think = false), about = home::about), lazy = "--lazy" in args)
                }
            }
        }
        else -> println(USAGE)
    }
}

/** `peresos doctor`: are Ollama and Gemma there, can it write its home, and is there a desktop for the window. */
private suspend fun doctor(
    fix: Boolean,
    report: Boolean,
) {
    say(whereItRuns())
    println()
    // No time limit on a request: pulling Gemma takes minutes.
    HttpClient(CIO) { engine { requestTimeout = 0 } }.use { client ->
        val doctor = Doctor(home, client, gemma = model(client, think = false))
        val checks = doctor.examine(fix) { check -> check.line().lines().forEach { println("  $it") } }
        println()
        val failed = checks.count { it.verdict == Verdict.FAIL }
        say(if (failed == 0) "Peresós has everything it needs. Feed it: peresos feed \"...\"" else "Peresós cannot eat until ${if (failed == 1) "the line marked [no] is fixed" else "the lines marked [no] are fixed"}.")
        if (report) {
            val file = doctor.save(doctor.report(checks))
            println()
            say("The report is in $file. It has no word of your pile, your diary or what Peresós knows about you.")
            say("To report a problem, attach it to an issue: https://github.com/deeptelar/peresos/issues/new")
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                if (yes("Open a new issue with the report filled in, in your browser? Nothing is sent until you submit it.")) Desktop.getDesktop().browse(URI(doctor.issue(checks)))
            }
        }
        if (failed > 0) exitProcess(1)
    }
}

/** `peresos uninstall`: removes Peresós the way it was installed, and its home only when the person says so. */
private fun uninstall() {
    val installed = installed() ?: return say("Peresós does not know how it was installed in ${System.getProperty("jpackage.app-path")}. Remove it the way you installed it.")
    if (installed == Installed.Source) return say("Peresós runs from its source here, so nothing is installed. Delete the folder of its source to remove it.")
    say("This removes:")
    installed.shown.forEach { println("    $it") }
    println()
    if (!yes("Remove Peresós from this computer?")) return say("Nothing was removed.")
    val forget = home.path.exists() && yes("Delete its home too, ${home.shown}, with your pile, its diary and what it finished?")
    // Its window would keep running from the files that go.
    val window = (if (home.path.exists()) home.window() else AutoCloseable {}) ?: return say("Its window is open. Quit Peresós from its menu, then run peresos uninstall again.")
    window.use { uninstall(installed, home.path.takeIf { forget }, terminal = true) }
    println()
    say(if (installed is Installed.Windows) "Peresós leaves as soon as this command ends." else "Peresós is gone. Thank you for letting it eat your backlog.")
    if (!forget && home.path.exists()) say("Your pile and its diary stay in ${home.shown}. Delete that folder to forget them too.")
    say("Ollama and $modelName stay, for other apps. To free their space too: ollama rm $modelName")
}

/** Asks [question] in the terminal: only y or yes is a yes, and no answer is a no. */
private fun yes(question: String): Boolean {
    print("  $question [y/N] ")
    return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
}

/** What went wrong, in words for the person, and what to do about it. */
internal fun trouble(cause: Throwable): String {
    val chain = generateSequence(cause) { it.cause }.toList()
    return when {
        chain.any { it is ConnectException } -> "Peresós could not reach Gemma. Is Ollama running? peresos doctor says what is missing."
        chain.any { it.message.orEmpty().let { message -> "not found" in message && Home.modelName in message } } ->
            "Ollama has no ${Home.modelName} yet. peresos doctor --fix pulls it."
        else -> "Something went wrong: ${chain.last().message ?: chain.last()::class.simpleName}"
    }
}

/** For the commands that only read the checkpoint and never call a model. */
internal object Unused : ChatModel {
    override suspend fun chat(request: ChatRequest): ChatResponse = error("This command does not call a model.")
}

/** Wakes it. A [lazy] wake picks the easiest item of the pile, not the oldest, and does it the laziest way. */
private suspend fun wake(
    graph: CompiledGraph<Sloth>,
    lazy: Boolean,
) {
    val config = home.config
    val today = LocalDate.now().toString()
    var result =
        when (val last = graph.lastResult(config)) {
            null -> chewing(lazy) { graph.invoke(Sloth(today, lazy = lazy), config) }
            is GraphResult.Interrupted if SWALLOW in last.nextNodes -> last.also { say("Peresós still has this in its mouth, from ${it.state.today}.") }
            is GraphResult.Interrupted -> chewing(last.state.lazy) { graph.resume(config) } // the last wake stopped halfway, say Ollama was not running
            is GraphResult.Completed -> chewing(lazy) { graph.invoke(last.state.copy(today = today, lazy = lazy), config) }
        }

    while (result is GraphResult.Interrupted) {
        val answer = askFor(result.state) ?: return say("Peresós holds it in its mouth until you are ready. Run peresos again to answer.")
        if (answer == Answer.AGAIN) say("Peresós chews it again. Slowly.")
        result = graph.resume(config) { it.copy(answer = answer) }
    }
    report(result.state)
}

private suspend fun <T> chewing(
    lazy: Boolean,
    run: suspend () -> T,
): T {
    say(if (lazy) "Peresós wakes up and looks for the easiest thing on the pile. Lazily." else "Peresós wakes up and looks at the pile. Slowly.")
    return run()
}

private fun askFor(sloth: Sloth): Answer? {
    val bite = checkNotNull(sloth.bite)
    println()
    sloth.why?.let { say("It picked the easiest one: $it") }
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
        // The old console of Windows prints the codes that move the cursor; Windows Terminal moves it.
        (!System.getProperty("os.name").startsWith("Windows") || System.getenv("WT_SESSION") != null) &&
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
      peresos --lazy          it picks the easiest item instead, and does it the laziest way
      peresos pile            what is still on the pile
      peresos diary           what it wrote in its diary
      peresos wear sunglasses dress it up: sunglasses, cap, barretina, coffee (peresos wear nothing to undress)
      peresos window          open its home in a window, where you can do all of this with buttons
      peresos place "town"    show that town's weather in the window, from Open-Meteo (peresos place nothing to stop)
      peresos doctor          check that it has all it needs (--fix pulls Gemma, --report writes a file for an issue)
      peresos uninstall       remove it from this computer; it asks before it deletes your pile

    It runs on $modelName in your local Ollama (PERESOS_MODEL changes it). Nothing leaves your computer,
    except the town you give peresos place.
    """.trimIndent()
