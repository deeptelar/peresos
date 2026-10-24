package dev.deeptelar.peresos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphSession
import dev.deeptelar.telar.GraphSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the window shows, and what its buttons do. The wake runs in a [GraphSession] of Telar: the
 * window draws its [GraphSnapshot] and calls start and resume, and the checkpoint in [home] is the
 * same one the terminal uses.
 *
 * @param hungry eat even if it already ate today, for a demo.
 * @param today the day, as `2026-10-23`.
 * @param meteo asks for the weather of the person's town, if they chose one. `null` never asks.
 * @param season and [weather] show this season or weather instead of the real one, to try them.
 * @param installed how it was installed, for Uninstall and Update on the About page, which it lacks
 *   when this is `null`: run from its source, or from a place it does not know.
 * @param updater asks GitHub for a newer version and installs it, when the person asks. `null` never asks.
 */
internal class SlothModel(
    private val home: Home,
    graph: CompiledGraph<Sloth>,
    private val scope: CoroutineScope,
    val hungry: Boolean = false,
    private val today: () -> String = { LocalDate.now().toString() },
    private val meteo: OpenMeteo? = null,
    season: Season? = null,
    weather: Weather? = null,
    val installed: Installed? = installed()?.takeIf { it != Installed.Source },
    private val updater: Updater? = null,
) {
    val session: GraphSession<Sloth> = GraphSession(graph, home.config, scope)

    var pile: List<String> by mutableStateOf(home.pile.items())
        private set
    var wearing: List<Item> by mutableStateOf(home.wearing())
        private set
    var about: String by mutableStateOf(home.about())
        private set

    /** A terminal has it awake, so the window waits. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** Why the last run stopped, in words for the person. */
    var trouble: String? by mutableStateOf(null)
        private set

    /** The town of the weather, chosen in the window or with `peresos place`. */
    var place: Place? by mutableStateOf(home.place())
        private set

    /** Why the last town could not be found. */
    var placeTrouble: String? by mutableStateOf(null)
        private set

    private val forcedSeason = season
    private val forcedWeather = weather

    /** The weather outside, while it is known: clear when no town is chosen. */
    var weather: Weather by mutableStateOf(weather ?: Weather.CLEAR)
        private set

    /** The day, as `2026-10-23`. It turns at midnight also while the window stays open, so the window can wake it again. */
    var day: String by mutableStateOf(today())
        private set

    /** The season, from the date and the half of the world the town is in. */
    val season: Season get() = forcedSeason ?: season(LocalDate.parse(day), place?.southern == true)

    /** It ate in this window, since it opened: a new phase is news. */
    var ateHere: Boolean by mutableStateOf(false)
        private set

    init {
        scope.launch {
            session.snapshot.collect { snapshot ->
                if (snapshot is GraphSnapshot.Stopped && snapshot.cause != null) {
                    trouble = trouble(snapshot.cause!!)
                    home.log.error("The wake stopped", snapshot.cause!!)
                }
                // Also after a stop: it may have swallowed before the diary failed.
                if (snapshot !is GraphSnapshot.Running && snapshot !is GraphSnapshot.Restoring) pile = home.pile.items()
            }
        }
        session.restore()
        // The weather changes slowly: a look every half hour is plenty.
        scope.launch {
            while (true) {
                lookOutside()
                delay(30.minutes)
            }
        }
        // The terminal shares the home: when it feeds, dresses or wakes the sloth, the window shows it.
        scope.launch {
            var seen = home.version()
            while (true) {
                delay(2.seconds)
                day = today()
                val now = home.version()
                if (now != seen && session.snapshot.value !is GraphSnapshot.Running) refresh()
                seen = now
            }
        }
    }

    /** Reads everything again, in case the terminal changed it. */
    fun refresh() {
        pile = home.pile.items()
        wearing = home.wearing()
        about = home.about()
        if (home.place() != place) {
            place = home.place()
            scope.launch { lookOutside() }
        }
        busy = false
        synchronized(turn) { if (session.snapshot.value !is GraphSnapshot.Running) session.restore() }
    }

    /**
     * A click and the look every two seconds take turns: a restore that the look sends just after a
     * click would cancel the run the click started, and the person's answer with it.
     */
    private val turn = Any()

    /** Opens the folder of the finished versions it swallowed, where the desktop can. */
    val openDone: (() -> Unit)? =
        Desktop.isDesktopSupported().takeIf { it && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }?.let {
            { Desktop.getDesktop().open(home.pile.done.toFile().also { it.mkdirs() }) }
        }

    /** Opens the README of Peresós on GitHub in the browser, where the desktop can: how it works, at length. */
    val openGuide: (() -> Unit)? =
        Desktop.isDesktopSupported().takeIf { it && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }?.let {
            { Desktop.getDesktop().browse(URI(GUIDE)) }
        }

    /** Its home, as the person would type it, to say what Uninstall can forget. */
    val homeShown: String = home.shown

    /** Uninstall was pressed, and it removes itself. */
    var leaving: Boolean by mutableStateOf(false)
        private set

    /** It removed itself, and the window says goodbye before it quits. */
    var gone: Boolean by mutableStateOf(false)
        private set

    /** Why it could not remove itself, and what the person can do instead. */
    var stayTrouble: String? by mutableStateOf(null)
        private set

    /**
     * Removes Peresós from this computer, and its home too when the person [forget]s it, then quits
     * with [onGone] after a goodbye. On Windows the files go once it has quit.
     */
    fun uninstall(
        forget: Boolean,
        onGone: () -> Unit,
    ) {
        val installed = installed ?: return
        leaving = true
        stayTrouble = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { uninstall(installed, home.path.takeIf { forget }, terminal = false) }
                gone = true
                delay(2.seconds)
                onGone()
            } catch (e: IOException) {
                home.log.error("Uninstall", e)
                stayTrouble = e.message
            } finally {
                leaving = false
            }
        }
    }

    /** What the window knows of a newer version, once the person asked; `null` before. */
    var update: Update? by mutableStateOf(null)
        private set

    /** Whether it can update itself: it knows how it was installed, and may ask GitHub. */
    val canUpdate: Boolean get() = installed != null && updater != null

    /** Asks GitHub whether there is a newer version than this one. */
    fun checkUpdate() {
        val updater = updater ?: return
        update = Update.Checking
        scope.launch {
            update =
                try {
                    val latest = updater.latest()
                    when {
                        latest == null -> Update.Available("A build to try, from ${updater.from}.")
                        isNewer(latest, updater.current) -> Update.Available("Peresós $latest is out.")
                        else -> Update.Latest
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Update.Failed("Could not ask GitHub: ${e.message}")
                }
        }
    }

    /**
     * Installs the newer version, then opens the window again: on Windows, PowerShell does both once
     * [onQuit] has quit this one; elsewhere a new window opens as this one quits.
     */
    fun installUpdate(onQuit: () -> Unit) {
        val updater = updater ?: return
        val installed = installed ?: return
        update = Update.Installing
        scope.launch {
            try {
                withContext(Dispatchers.IO) { updater.update(installed, terminal = false) }
                if (installed !is Installed.Windows) reopen()
                onQuit()
            } catch (e: IOException) {
                home.log.error("Update", e)
                update = Update.Failed(e.message.orEmpty())
            }
        }
    }

    /** Starts a new window from the launcher, which now runs the new version, a moment after this one quits. */
    private fun reopen() {
        val launcher = System.getProperty("jpackage.app-path") ?: return
        ProcessBuilder("sh", "-c", "sleep 2; exec \"\$0\" window", launcher)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /** It already ate today, and one item a day is plenty. */
    fun ateToday(sloth: Sloth?): Boolean = !hungry && sloth?.lastMeal == day

    fun isFull(sloth: Sloth?): Boolean = sloth != null && sloth.roundness > 0 && pile.isEmpty()

    /**
     * Wakes it: it looks at the pile and chews the oldest item, or continues a wake that stopped halfway.
     * A [lazy] wake picks the easiest item instead, and does it the laziest way.
     */
    fun wake(lazy: Boolean = false) =
        awake {
            ateHere = false
            val snapshot = session.snapshot.value
            when {
                snapshot is GraphSnapshot.Completed -> session.start(snapshot.state.copy(today = today(), lazy = lazy))
                // A wake that stopped halfway, say because Ollama was not running, continues from its last step.
                (snapshot is GraphSnapshot.Interrupted || snapshot is GraphSnapshot.Stopped) && snapshot.state != null -> session.resume()
                else -> session.start(Sloth(today(), lazy = lazy))
            }
        }

    /** The person's answer to what it chewed. */
    fun answer(answer: Answer) =
        awake {
            if (answer != Answer.AGAIN) ateHere = true
            session.resume { it.copy(answer = answer) }
        }

    fun feed(item: String) {
        if (item.isBlank()) return
        home.pile.add(item)
        pile = home.pile.items()
    }

    /** Puts [item] on, or takes it off when it wears it. */
    fun toggle(item: Item) {
        if (item in wearing) home.takeOff(item) else home.putOn(listOf(item))
        wearing = home.wearing()
    }

    /** Shows the weather of the town called [name], or none when [name] is blank. */
    fun choosePlace(name: String) {
        scope.launch {
            val found = if (name.isBlank()) null else runCatching { meteo?.find(name) }.getOrNull()
            placeTrouble = if (name.isNotBlank() && found == null) "Open-Meteo knows no town called ${name.trim()}, or could not be reached." else null
            if (name.isNotBlank() && found == null) return@launch
            home.settle(found)
            place = found
            lookOutside()
        }
    }

    private suspend fun lookOutside() {
        val meteo = meteo
        if (forcedWeather != null || meteo == null) return
        val place = place ?: return run { weather = Weather.CLEAR }
        runCatching { meteo.now(place) }.onSuccess { weather = it } // offline: it keeps the last weather it saw
    }

    fun learn(fact: String) {
        home.learn(fact)
        about = home.about()
    }

    /** Runs [command] while it holds the lock of its home, so a terminal does not wake it at the same time. */
    private fun awake(command: () -> Job) {
        synchronized(turn) {
            val awake = home.awake()
            busy = awake == null
            if (awake == null) return
            trouble = null
            command().invokeOnCompletion { awake.close() }
        }
    }
}

/** Where the README of Peresós is, for the About page of the window. */
internal const val GUIDE = "https://github.com/deeptelar/peresos#readme"

/** Where Update on the About page is. */
internal sealed interface Update {
    data object Checking : Update

    data object Latest : Update

    /** A newer version is out, and [news] says which. */
    data class Available(val news: String) : Update

    data object Installing : Update

    /** It could not check or update: [message] says why, and what to do instead. */
    data class Failed(val message: String) : Update
}
