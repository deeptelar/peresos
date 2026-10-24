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
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
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
        "gemma" -> gemma(args.getOrNull(1))
        "desktop" -> launcher(Opener.DESKTOP, args.getOrNull(1))
        "autostart" -> launcher(Opener.LOGIN, args.getOrNull(1))
        // For install.ps1: the shortcut of the Start menu, which PowerShell may not be allowed to make.
        "shortcut" -> writeShortcut(System.getProperty("jpackage.app-path").let { Path.of(it).resolveSibling("peresos-window.exe").toString() }, Path.of(args[1]))
        "uninstall" -> uninstall()
        "update" -> HttpClient(CIO) { engine { requestTimeout = 0 } }.use { client -> update(Updater(client)) }
        "--version", "version" -> println("peresos $VERSION")
        "window" ->
            if ("--here" in args || !detach(args)) {
                window(home, hungry = "--hungry" in args, season = option(args, "season", Season.entries), weather = option(args, "weather", Weather.entries))
            }
        // Opened from the Finder, the app of macOS has no terminal: it opens the window.
        null if MAC && System.getenv("TERM") == null && System.console() == null -> window(home)
        null, "--hungry", "--lazy" -> {
            val awake = home.awake() ?: return say("Peresós is awake in its window, or in another terminal. Answer it there.")
            awake.use {
                HttpClient(CIO).use { client ->
                    wake(sloth(model(client), pile, hungry = "--hungry" in args, diarist = model(client, think = false), about = home::about, gemma = home::gemma), lazy = "--lazy" in args)
                }
            }
        }
        else -> println(USAGE)
    }
}

/**
 * Starts the window as a process of its own, which goes on when the terminal closes, and says so.
 * `false` when there is no launcher to start, as from the source, or no desktop: then it runs here,
 * where its errors show.
 */
private fun detach(args: Array<String>): Boolean {
    val launcher = System.getProperty("jpackage.app-path")?.let { Path.of(it) } ?: return false
    if (GraphicsEnvironment.isHeadless()) return false
    val windows = System.getProperty("os.name").startsWith("Windows")
    // On Windows the window's own launcher, which opens no console.
    val program = (if (windows) launcher.resolveSibling("peresos-window.exe").takeIf { it.exists() } else null) ?: launcher
    val window = listOf(program.toString()) + args.toList() + "--here"
    val command =
        when {
            windows -> window
            // Its own session: the terminal's hangup never reaches it.
            onPath("setsid") != null -> listOf("setsid") + window
            else -> listOf("nohup") + window
        }
    ProcessBuilder(command)
        .fresh()
        .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    say("Peresós is in your tray. You can close this terminal.")
    return true
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

/** `peresos update`: asks GitHub for the latest release, and installs it the way Peresós was installed, if the person says so. */
private suspend fun update(updater: Updater) {
    val installed = installed() ?: return say("Peresós does not know how it was installed in ${System.getProperty("jpackage.app-path")}. Install it again: ${Updater.ONE_LINE}")
    if (installed == Installed.Source) return say("Peresós runs from its source here: update it with git pull.")
    val latest =
        try {
            updater.latest()
        } catch (e: IOException) {
            return say("Could not ask GitHub for the latest version: ${e.message}")
        }
    if (latest == null) {
        if (!yes("Install Peresós from ${updater.from} over ${updater.current}?")) return say("Nothing changed.")
    } else {
        if (!isNewer(latest, updater.current)) return say("Peresós ${updater.current} is the latest version.")
        say("Peresós $latest is out, and you have ${updater.current}. What is new: ${Updater.RELEASES}")
        if (!yes("Update it now?")) return say("Nothing changed. Update it whenever you like: peresos update")
    }
    // Its window would keep running from the files that change.
    val window = home.window() ?: return say("Its window is open. Quit Peresós from its menu, then run peresos update again.")
    window.use {
        try {
            updater.update(installed, terminal = true)
        } catch (e: IOException) {
            return say(e.message.orEmpty())
        }
    }
}

/** `peresos gemma on|off`: whether it prepares items with Gemma, or brings them as they are, for a computer without Ollama. */
private fun gemma(choice: String?) {
    when (choice) {
        "on" -> home.useGemma(true)
        "off" -> home.useGemma(false)
        null -> Unit
        else -> return say("peresos gemma on, or peresos gemma off.")
    }
    say(
        if (home.gemma()) {
            "Peresós uses $modelName in your Ollama: it prepares the finished version of each item. Without Ollama: peresos gemma off"
        } else {
            "Peresós works without Gemma: it brings you each item as it is, and you say done, bin it or later. With Ollama: peresos gemma on"
        },
    )
}

/** `peresos desktop on|off` and `peresos autostart on|off`: an icon on the desktop, and the start at login, into the tray. */
private fun launcher(
    opener: Opener,
    choice: String?,
) {
    val launchers = Launchers.here() ?: return say("Peresós runs from its source here: install it to put it on the desktop or start it at login.")
    val what = if (opener == Opener.DESKTOP) "an icon on your desktop" else "the start at login, into the tray"
    val command = if (opener == Opener.DESKTOP) "peresos desktop" else "peresos autostart"
    when (choice) {
        "on", "off" ->
            try {
                launchers.turn(opener, choice == "on")
            } catch (e: IOException) {
                return say(e.message.orEmpty())
            }
        null -> Unit
        else -> return say("$command on, or $command off.")
    }
    say(if (launchers.isOn(opener)) "Peresós has $what: ${launchers.file(opener)}. To take it away: $command off" else "Peresós has no $what. To add it: $command on")
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
        chain.any { it is ConnectException } -> "Peresós could not reach Gemma. Is Ollama running? peresos doctor says what is missing. Without Ollama: peresos gemma off"
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
    // Without Gemma it brings the item as it is: there is no other version to try.
    say(if (sloth.raw) "It brought you this one, unchewed:" else "It chewed this one: ${bite.title}  (${bite.kind.name.lowercase()})")
    println()
    wrap(bite.finished).forEach { println("    $it") }
    println()
    print(if (sloth.raw) "  [y] done   [n] bin it   [l] later   (Enter: decide another day)  " else "  [y] take it   [n] bin it   [l] later   [a] another try   (Enter: decide another day)  ")
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes", "d", "done" -> Answer.YES
        "n", "no" -> Answer.NO
        "l", "later" -> Answer.LATER
        "a", "again" -> Answer.AGAIN.takeUnless { sloth.raw }
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
                Answer.YES -> say(if (sloth.raw) "Done. It is noted in ${home.shown}/done." else "Swallowed. The finished version is in ${home.shown}/done.")
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

/**
 * `peresos feed`: puts what is typed or piped on the pile, or the files it is given, one item each.
 * With `--notify`, as from the menu of the file manager, the desktop says what happened.
 */
private fun feed(
    pile: FilePile,
    args: List<String>,
) {
    val notify = "--notify" in args
    val words = args - "--notify"
    val paths = words.map { runCatching { Path.of(it) }.getOrNull() }
    if (paths.isNotEmpty() && paths.all { it?.isRegularFile() == true }) return feedFiles(pile, paths.filterNotNull(), notify)
    // Names of files it eats, such as a mistyped photo.jpg, are not an item to do.
    if (paths.isNotEmpty() && paths.all { it != null && Food.of(it) != null }) {
        val missing = paths.first { it?.isRegularFile() != true }
        return say("There is no file at $missing.").also { if (notify) notifyDesktop("Peresós cannot eat that", "There is no file at $missing.") }
    }
    val item = words.joinToString(" ").ifBlank { generateSequence(::readLine).joinToString("\n") }
    if (item.isBlank()) return println(USAGE)
    pile.add(item)
    say("Peresós looks at the pile: ${items(pile.items().size)}. It will get to it. Eventually.")
}

private fun feedFiles(
    pile: FilePile,
    files: List<Path>,
    notify: Boolean,
) {
    val failed =
        files.mapNotNull { file ->
            try {
                pile.addFile(file)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }
    val fed = files.size - failed.size
    val news =
        when {
            fed == 0 -> null
            files.size == 1 -> "Peresós put ${files.single().fileName} on the pile: ${items(pile.items().size)}."
            else -> "Peresós put $fed files on the pile: ${items(pile.items().size)}."
        }
    news?.let(::say)
    failed.forEach(::say)
    if (notify) notifyDesktop(if (fed == 0) "Peresós cannot eat that" else "Peresós has more to eat", listOfNotNull(news, failed.firstOrNull()).joinToString(" "))
}

private fun items(count: Int) = "$count ${if (count == 1) "item" else "items"}"

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
    items.forEachIndexed { index, item -> println("  ${index + 1}. ${shown(item).lines().first().take(70)}") }
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
      peresos feed photo.jpg  or a photo, a PDF or a text file: Gemma reads it on this computer
      peresos about "fact"    tell it something about you that helps, like when you are home
      peresos                 wake it: it eats the oldest item and shows you the finished version
      peresos --lazy          it picks the easiest item instead, and does it the laziest way
      peresos pile            what is still on the pile
      peresos diary           what it wrote in its diary
      peresos wear sunglasses dress it up: sunglasses, cap, barretina, coffee (peresos wear nothing to undress)
      peresos window          open its home in a window, where you can do all of this with buttons; the terminal
                              can close (--here keeps it in the terminal)
      peresos place "town"    show that town's weather in the window, from Open-Meteo (peresos place nothing to stop)
      peresos doctor          check that it has all it needs (--fix pulls Gemma, --report writes a file for an issue)
      peresos gemma off       work without Ollama: it brings each item as it is (peresos gemma on to use Gemma)
      peresos desktop on      an icon on your desktop that opens its window (off to take it away)
      peresos autostart on    start it when you sign in, into the tray (off to stop that)
      peresos update          install its latest version, if there is a newer one (it asks GitHub)
      peresos uninstall       remove it from this computer; it asks before it deletes your pile

    It runs on $modelName in your local Ollama (PERESOS_MODEL changes it). Nothing leaves your computer,
    except the town you give peresos place, and peresos update, which asks GitHub for the latest version.
    """.trimIndent()
