package dev.deeptelar.peresos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeptelar.telar.GraphSnapshot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalTime

/** The pages of the window, in the bar at its bottom. */
private enum class Page { NOW, PILE, DIARY, WEAR, YOU }

/**
 * Everything in the window, in one column about the size of a phone: the habitat at the top, the
 * page below it, and the bar of pages at the bottom.
 *
 * @param header the bar to drag the window by, when it has no frame of its own.
 */
@Composable
internal fun SlothScreen(
    model: SlothModel,
    night: Boolean = LocalTime.now().hour !in 7..19,
    header: @Composable () -> Unit = {},
) {
    val snapshot by model.session.snapshot.collectAsState()
    val sloth = snapshot.state
    val tick = rememberTick()
    val onTop = model.isFull(sloth)
    val roundness = sloth?.roundness ?: 0
    val frames = remember(roundness, onTop, model.wearing) { Portrait.pictures(roundness, onTop, model.wearing) }
    var page by remember { mutableStateOf(Page.NOW) }
    // The last touch, kept after its reaction ends, to count the touches in a row. The reaction ends
    // with the first tick after it, which draws the screen again.
    var last by remember { mutableStateOf<Reaction?>(null) }
    val reaction = last?.takeIf { System.currentTimeMillis() - it.at < REACTION_MS }
    val touched = remember(reaction, frames) { reaction?.let { Portrait.touched(roundness, onTop, model.wearing, it.touch, it.fedUp) } }
    Column(Modifier.fillMaxSize().drawBehind { planks() }) {
        header()
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Habitat(frames, onTop, night, model.season, model.weather, tick, Modifier.fillMaxSize(), touched, shake(reaction, tick)) { touch ->
                val now = System.currentTimeMillis()
                val count = last?.takeIf { now - it.at < IN_A_ROW_MS }?.count?.plus(1) ?: 1
                last = Reaction(touch, said(touch, feeling(model, snapshot), count, model.pile.size), count, tick, now)
            }
            // What it says when touched, above its head.
            reaction?.let { PixelText(it.line, Modifier.align(Alignment.TopCenter).padding(top = 8.dp).widthIn(max = 300.dp).pixelBox(Ink.paper).padding(horizontal = 8.dp, vertical = 4.dp), size = SMALL) }
            PixelText(outside(model), Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp), size = SMALL, color = Ink.paper)
        }
        val grew = model.ateHere && snapshot is GraphSnapshot.Completed && sloth?.rest == Rest.NONE && Portrait.phase(roundness) != Portrait.phase(roundness - 1)
        PixelText(Portrait.label(roundness, grew), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), size = SMALL, color = if (grew) Ink.yellow else Ink.paper)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).pixelBox(Ink.paper).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (page) {
                Page.NOW -> Now(model, snapshot, tick)
                Page.PILE -> Pile(model)
                Page.DIARY -> Diary(sloth)
                Page.WEAR -> Wardrobe(model)
                Page.YOU -> About(model)
            }
        }
        PixelTabs(listOf("Now", "Pile ${model.pile.size}", "Diary", "Wear", "You"), page.ordinal, Modifier.padding(12.dp)) { page = Page.entries[it] }
    }
}

/** A tickle wiggles the sloth from side to side; a poke makes it jump once. */
private fun shake(
    reaction: Reaction?,
    tick: Int,
): Int {
    val since = tick - (reaction?.tick ?: return 0)
    return when {
        reaction.fedUp || reaction.touch == Touch.HEAD -> 0
        reaction.touch == Touch.BELLY -> if (since < 8) since % 2 else 0
        else -> if (since < 2) 1 else 0
    }
}

/** The top of a window without a frame: its name, to drag it by, and buttons to hide it in the tray or quit. */
@Composable
internal fun TitleBar(
    onHide: (() -> Unit)?,
    onQuit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelText("Peresós", Modifier.weight(1f), color = Ink.paper)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onHide?.let { PixelButton("Hide", Ink.paperDark, it, small = true) }
            PixelButton("Quit", Ink.paperDark, onQuit, small = true)
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

/** The season and the town, in a corner of the habitat: the sky shows the weather. */
private fun outside(model: SlothModel): String {
    val season = model.season.name.lowercase().replaceFirstChar { it.uppercase() }
    return model.place?.let { "$season · ${it.name.substringBefore(",")}" } ?: season
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
            Line("Awake in a terminal.")
            PixelButton("Look again", Ink.yellow, model::refresh)
        }
        snapshot is GraphSnapshot.Restoring -> Line("Peresós opens one eye.")
        snapshot is GraphSnapshot.Running -> {
            Title("Munch, munch...")
            Line(
                when (snapshot.nodes.firstOrNull()) {
                    "pick" -> "Picking the easiest."
                    "chew" -> "Chewing. 10 to 60 seconds."
                    "swallow" -> "Swallowing."
                    "diary" -> "Writing its diary."
                    else -> "Waking up."
                },
            )
            PixelProgress(tick)
        }
        snapshot is GraphSnapshot.Interrupted && SWALLOW in snapshot.nextNodes && sloth?.bite != null -> Bite(model, sloth.bite, sloth.why)
        snapshot is GraphSnapshot.Interrupted || snapshot is GraphSnapshot.Stopped -> {
            Title("Hmm.")
            Line(model.trouble ?: "Stopped halfway.")
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
    // What it chewed scrolls, however long; the answers stay in sight at the bottom.
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelText(bite.kind.name.lowercase(), size = SMALL, color = Ink.faint)
        Title(bite.title)
        Box(Modifier.fillMaxWidth().pixelBox(Ink.field, raised = false)) {
            SelectionContainer { PixelText(bite.finished.trim(), Modifier.fillMaxWidth().padding(14.dp), size = BODY) }
        }
        why?.let { Hint("Why: $it") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelButton("Take it", Ink.green, { model.answer(Answer.YES) })
        PixelButton("Bin it", Ink.red, { model.answer(Answer.NO) })
        PixelButton("Later", Ink.yellow, { model.answer(Answer.LATER) })
        PixelButton("Another try", Ink.blue, { model.answer(Answer.AGAIN) })
        PixelButton("Copy", Ink.paperDark, { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(bite.finished.trim()), null) })
    }
}

/** Between wakes: what happened in the last one, and the button to wake it. */
@Composable
private fun ColumnScope.Rested(
    model: SlothModel,
    sloth: Sloth?,
) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LastWake(model, sloth)
    }
    val reason =
        when {
            model.ateToday(sloth) -> "Ate today. Back tomorrow."
            model.pile.isEmpty() -> "Nothing on the pile."
            else -> null
        }
    reason?.let { Hint(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelButton("Wake it", Ink.green, { model.wake() }, enabled = reason == null)
        PixelButton("Something easy", Ink.yellow, { model.wake(lazy = true) }, enabled = reason == null)
    }
}

/** What happened in the last wake, if it was today, or that it sleeps. */
@Composable
private fun LastWake(
    model: SlothModel,
    sloth: Sloth?,
) {
    val woke = sloth != null && sloth.lastWake == model.day
    if (woke && sloth.sleptDays > 1) Line("Slept ${sloth.sleptDays} days.")
    if (woke && sloth.rest == Rest.NONE) {
        val meal = sloth.meals.last()
        Title(
            when (meal.answer) {
                Answer.YES -> "Gulp! Swallowed."
                Answer.NO -> "Ptooey! Spat out."
                else -> "Back on the pile."
            },
        )
        Line("“${meal.title}”")
        sloth.diary.lastOrNull()?.let { Box(Modifier.fillMaxWidth().pixelBox(Ink.field, raised = false).padding(12.dp)) { PixelText("“${it.text}”", size = BODY, color = Ink.faint) } }
        if (meal.answer == Answer.YES) model.openDone?.let { open -> PixelButton("Open done folder", Ink.paperDark, open, small = true) }
    } else {
        Title("Zzz...")
    }
}

@Composable
private fun Pile(model: SlothModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Asking("Something you keep putting off", "Feed it", model::feed)
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
    if (entries.isEmpty()) return Hint("Nothing yet.")
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
        Item.entries.forEach { item -> PixelCheck(item in model.wearing, item.name.lowercase()) { model.toggle(item) } }
    }
}

@Composable
private fun About(model: SlothModel) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Asking("A fact about you", "Tell it", model::learn)
        model.about.lines().filter { it.isNotBlank() }.forEach { PixelText(it, size = BODY) }
        PixelText("WEATHER", Modifier.padding(top = 8.dp), size = SMALL, color = Ink.faint)
        Asking(model.place?.name ?: "Your town", "Set", model::choosePlace)
        model.placeTrouble?.let { Hint(it) }
        if (model.place != null) {
            PixelButton("Stop", Ink.paperDark, { model.choosePlace("") }, small = true)
        } else {
            Hint("Only the town is sent, to Open-Meteo.")
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
