package dev.deeptelar.peresos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.deeptelar.peresos.Home.Companion.model
import dev.deeptelar.telar.GraphSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalTime

/**
 * Opens its home in a window, `peresos window`. It shares [home] with the terminal.
 *
 * @param season and [weather] show this season or weather instead of the real one, to try them.
 */
fun window(
    home: Home,
    hungry: Boolean = false,
    season: Season? = null,
    weather: Weather? = null,
) = application {
    val client = remember { HttpClient(CIO) }
    val scope = rememberCoroutineScope()
    val model =
        remember {
            val graph = sloth(model(client), home.pile, hungry, diarist = model(client, think = false), about = home::about)
            SlothModel(home, graph, scope, hungry, meteo = OpenMeteo(client), season = season, weather = weather)
        }
    val icon = remember { PicturePainter(Portrait.pictures(0).first()) }
    Window(
        onCloseRequest = {
            client.close()
            exitApplication()
        },
        state = rememberWindowState(size = DpSize(1120.dp, 760.dp)),
        title = "Peresós",
        icon = icon,
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(960, 640) }
        SlothScreen(model)
    }
}

/** Everything in the window: the habitat on the left, and on the right a board with what it is doing and its tabs. */
@Composable
internal fun SlothScreen(
    model: SlothModel,
    night: Boolean = LocalTime.now().hour !in 7..19,
) {
    val snapshot by model.session.snapshot.collectAsState()
    val sloth = snapshot.state
    val tick = rememberTick()
    val onTop = model.isFull(sloth)
    val roundness = sloth?.roundness ?: 0
    val frames = remember(roundness, onTop, model.wearing) { Portrait.pictures(roundness, onTop, model.wearing) }
    Row(Modifier.fillMaxSize().drawBehind { planks() }) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Habitat(frames, onTop, night, model.season, model.weather, tick, Modifier.weight(1f).fillMaxWidth())
            val grew = model.ateHere && snapshot is GraphSnapshot.Completed && sloth?.rest == Rest.NONE && Portrait.phase(roundness) != Portrait.phase(roundness - 1)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelText(Portrait.caption(roundness, grew, onTop), Modifier.weight(1f), size = BODY, color = if (grew) Ink.yellow else Ink.paper)
                PixelText(outside(model, night), size = SMALL, color = Ink.paperDark)
            }
        }
        Column(Modifier.width(480.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 470.dp).pixelBox(Ink.paper).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Now(model, snapshot, tick)
            }
            Tabs(model, sloth, Modifier.weight(1f))
        }
    }
}

/** The bark of the board behind everything: planks with their seams and a few nails. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.planks() {
    drawRect(Ink.wood)
    val p = PX.toPx()
    val plank = 14 * p
    var y = 0f
    var row = 0
    while (y < size.height) {
        drawRect(Ink.woodDark, Offset(0f, y + plank - p), Size(size.width, p))
        drawRect(Ink.woodLight, Offset(0f, y), Size(size.width, p))
        // A seam between two planks, in a different place on every row.
        val seam = (row * 173 % 11 + 2) * size.width / 14
        drawRect(Ink.woodDark, Offset(seam, y), Size(p, plank))
        drawRect(Ink.woodDark, Offset(seam + 3 * p, y + plank / 2 - p / 2), Size(p, p))
        y += plank
        row++
    }
}

/** The season and the weather, under the habitat. */
private fun outside(
    model: SlothModel,
    night: Boolean,
): String {
    val season = model.season.name.lowercase().replaceFirstChar { it.uppercase() }
    val sky =
        when (model.weather) {
            Weather.CLEAR -> if (night) "clear night" else "sunny"
            Weather.CLOUDY -> "cloudy"
            Weather.FOG -> "foggy"
            Weather.RAIN -> "rain"
            Weather.SNOW -> "snow"
            Weather.STORM -> "storm"
        }
    return model.place?.let { "$season · $sky in ${it.name.substringBefore(",")}" } ?: season
}

/** What it is doing now, and the buttons for it. */
@Composable
private fun ColumnScope.Now(
    model: SlothModel,
    snapshot: GraphSnapshot<Sloth>,
    tick: Int,
) {
    val sloth = snapshot.state
    when {
        model.busy -> {
            Line("Peresós is awake in a terminal. Answer it there, then come back.")
            PixelButton("Look again", Ink.yellow, model::refresh)
        }
        snapshot is GraphSnapshot.Restoring -> Line("Peresós opens one eye.")
        snapshot is GraphSnapshot.Running -> {
            Title("Munch, munch...")
            Line(
                when (snapshot.nodes.firstOrNull()) {
                    "pick" -> "It looks for the easiest thing on the pile. Lazily."
                    "chew" -> "It chews. Gemma is preparing the finished version, slowly: 10 to 60 seconds."
                    "swallow" -> "It swallows."
                    "diary" -> "It writes a line in its diary."
                    else -> "It wakes up and looks at the pile. Slowly."
                },
            )
            PixelProgress(tick)
        }
        snapshot is GraphSnapshot.Interrupted && SWALLOW in snapshot.nextNodes && sloth?.bite != null -> Bite(model, sloth.bite, sloth.why)
        snapshot is GraphSnapshot.Interrupted || snapshot is GraphSnapshot.Stopped -> {
            Title("Hmm.")
            Line(model.trouble ?: "The last wake stopped halfway.")
            PixelButton("Try again", Ink.yellow, { model.wake() })
        }
        else -> Rested(model, sloth)
    }
}

/** What it chewed, waiting for the person's answer. */
@Composable
private fun ColumnScope.Bite(
    model: SlothModel,
    bite: Bite,
    why: String?,
) {
    why?.let { Line("It picked the easiest one: $it") }
    PixelText("It chewed this one  ·  ${bite.kind.name.lowercase()}", size = SMALL, color = Ink.faint)
    Title(bite.title)
    // A long version scrolls in its own box, so the buttons under it stay in sight.
    Box(Modifier.fillMaxWidth().heightIn(max = 230.dp).pixelBox(Ink.field, raised = false)) {
        SelectionContainer { PixelText(bite.finished.trim(), Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp), size = BODY) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelButton("Take it", Ink.green, { model.answer(Answer.YES) })
        PixelButton("Bin it", Ink.red, { model.answer(Answer.NO) })
        PixelButton("Later", Ink.yellow, { model.answer(Answer.LATER) })
        PixelButton("Another try", Ink.blue, { model.answer(Answer.AGAIN) })
        PixelButton("Copy", Ink.paperDark, { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(bite.finished.trim()), null) })
    }
    Hint("Or close the window: it keeps it in its mouth until you come back.")
}

/** Between wakes: what happened in the last one, and the button to wake it. */
@Composable
private fun ColumnScope.Rested(
    model: SlothModel,
    sloth: Sloth?,
) {
    val woke = sloth != null && sloth.lastWake == model.day
    if (woke && sloth.sleptDays > 1) Line("It slept ${sloth.sleptDays} days. It feels wonderful.")
    if (woke && sloth.rest == Rest.NONE) {
        val meal = sloth.meals.last()
        Title(
            when (meal.answer) {
                Answer.YES -> "Gulp! Swallowed."
                Answer.NO -> "Ptooey! Spat out."
                else -> "Back on the pile."
            },
        )
        Line(
            when (meal.answer) {
                Answer.YES -> "“${meal.title}” is done. The finished version is in your done folder."
                Answer.NO -> "“${meal.title}”: one thing fewer to think about."
                else -> "“${meal.title}” went back to the bottom of the pile."
            },
        )
        if (sloth.emptied && model.pile.isEmpty()) Line("That was the last one. The pile is empty: Peresós ate everything. Nothing left to put off.")
        sloth.diary.lastOrNull()?.let { Box(Modifier.fillMaxWidth().pixelBox(Ink.field, raised = false).padding(12.dp)) { PixelText("Diary: “${it.text}”", size = BODY, color = Ink.faint) } }
        if (meal.answer == Answer.YES) model.openDone?.let { open -> PixelButton("Open the done folder", Ink.paperDark, open, small = true) }
    } else {
        Title("Zzz...")
        Line("Peresós is asleep. Wake it when you like: it eats one item of the pile a day.")
    }
    val reason =
        when {
            model.ateToday(sloth) -> "It already ate today. One item a day is plenty. Come back tomorrow."
            model.pile.isEmpty() && model.isFull(sloth) -> "It sleeps on top of its branch, full, until you feed it."
            model.pile.isEmpty() -> "The pile is empty. Feed it something below."
            else -> null
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelButton("Wake it", Ink.green, { model.wake() }, enabled = reason == null)
        PixelButton("Something easy", Ink.yellow, { model.wake(lazy = true) }, enabled = reason == null)
    }
    reason?.let { Hint(it) } ?: Hint("Wake it eats the oldest item. Something easy lets it pick the easiest one, done the laziest way.")
}

@Composable
private fun Tabs(
    model: SlothModel,
    sloth: Sloth?,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelTabs(listOf("Pile ${model.pile.size}", "Diary", "Wardrobe", "You"), tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth().pixelBox(Ink.paper).padding(16.dp)) {
            when (tab) {
                0 -> Pile(model)
                1 -> Diary(sloth)
                2 -> Wardrobe(model)
                else -> About(model)
            }
        }
    }
}

@Composable
private fun Pile(model: SlothModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Asking("The message, decision or note you keep avoiding", "Feed it", model::feed)
        if (model.pile.isEmpty()) Hint("Nothing on the pile.") else Hint("Oldest first: it eats the top one next.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(model.pile) { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelText("${index + 1}.", size = BODY, color = Ink.faint)
                    PixelText(item.lines().first(), size = BODY, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Diary(sloth: Sloth?) {
    val entries = sloth?.diary.orEmpty().asReversed()
    if (entries.isEmpty()) return Hint("The diary is empty. Peresós has not eaten anything yet.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(entries) { _, entry ->
            Column {
                PixelText(entry.day, size = SMALL, color = Ink.faint)
                PixelText(entry.text, size = BODY)
            }
        }
    }
}

@Composable
private fun Wardrobe(model: SlothModel) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Item.entries.forEach { item -> PixelCheck(item in model.wearing, item.name.lowercase(), item.description) { model.toggle(item) } }
        Hint("One item per place: a new hat replaces the old one. The terminal shows the same outfit.")
    }
}

@Composable
private fun About(model: SlothModel) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Asking("Something that helps, like when you are home", "Tell it", model::learn)
        Hint("It uses these facts every time it prepares something.")
        model.about.lines().filter { it.isNotBlank() }.forEach { PixelText(it, size = BODY) }
        PixelText("WEATHER", Modifier.padding(top = 8.dp), size = SMALL, color = Ink.faint)
        Asking(model.place?.name ?: "Your town, for its weather in the habitat", "Set", model::choosePlace)
        model.placeTrouble?.let { Hint(it) }
        if (model.place != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("The weather of ${model.place?.name}, from Open-Meteo. Only the town is sent.")
                PixelButton("Stop", Ink.paperDark, { model.choosePlace("") }, small = true)
            }
        } else {
            Hint("Without a town it shows the season only, and nothing leaves your computer.")
        }
    }
}

/** A text field and a button that hands its text to [onDone], then empties it. */
@Composable
private fun Asking(
    placeholder: String,
    button: String,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelField(text, { text = it }, placeholder, Modifier.weight(1f))
        PixelButton(button, Ink.green, {
            onDone(text)
            text = ""
        }, enabled = text.isNotBlank())
    }
}

@Composable
private fun Title(text: String) = PixelText(text, size = BIG)

@Composable
private fun Line(text: String) = PixelText(text)

@Composable
private fun Hint(text: String) = PixelText(text, size = SMALL, color = Ink.faint)
