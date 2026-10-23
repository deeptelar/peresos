package dev.deeptelar.peresos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphSession
import dev.deeptelar.telar.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.ConnectException
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

    /** The season, from the date and the half of the world the town is in. */
    val season: Season get() = forcedSeason ?: season(LocalDate.parse(today()), place?.southern == true)

    /** It ate in this window, since it opened: a new phase is news. */
    var ateHere: Boolean by mutableStateOf(false)
        private set

    init {
        scope.launch {
            session.snapshot.collect { snapshot ->
                if (snapshot is GraphSnapshot.Stopped && snapshot.cause != null) trouble = describe(snapshot.cause!!)
                if (snapshot is GraphSnapshot.Completed || snapshot is GraphSnapshot.Interrupted) pile = home.pile.items()
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
        if (session.snapshot.value !is GraphSnapshot.Running) session.restore()
    }

    fun day(): String = today()

    /** Opens the folder of the finished versions it swallowed, where the desktop can. */
    val openDone: (() -> Unit)? =
        Desktop.isDesktopSupported().takeIf { it && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }?.let {
            { Desktop.getDesktop().open(home.pile.done.toFile().also { it.mkdirs() }) }
        }

    /** It already ate today, and one item a day is plenty. */
    fun ateToday(sloth: Sloth?): Boolean = !hungry && sloth?.lastMeal == today()

    fun isFull(sloth: Sloth?): Boolean = sloth != null && sloth.roundness > 0 && pile.isEmpty()

    /** Wakes it: it looks at the pile and chews the oldest item, or continues a wake that stopped halfway. */
    fun wake() =
        awake {
            ateHere = false
            val snapshot = session.snapshot.value
            when {
                snapshot is GraphSnapshot.Completed -> session.start(snapshot.state.copy(today = today()))
                // A wake that stopped halfway, say because Ollama was not running, continues from its last step.
                (snapshot is GraphSnapshot.Interrupted || snapshot is GraphSnapshot.Stopped) && snapshot.state != null -> session.resume()
                else -> session.start(Sloth(today()))
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
        val awake = home.awake()
        busy = awake == null
        if (awake == null) return
        trouble = null
        command().invokeOnCompletion { awake.close() }
    }

    private fun describe(cause: Throwable): String {
        val chain = generateSequence(cause) { it.cause }
        if (chain.any { it is ConnectException }) return "Peresós could not reach Gemma. Is Ollama running? Start it with: ollama serve"
        return "Something went wrong while it chewed: ${chain.last().message ?: chain.last()::class.simpleName}"
    }
}
