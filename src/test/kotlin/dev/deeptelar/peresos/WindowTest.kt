package dev.deeptelar.peresos

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.test.ScriptedChatModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowTest {
    private val home = Home(Files.createTempDirectory("peresos"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bite = Bite(Kind.REPLY, "Boiler check on Tuesday", "Hi! Tuesday morning next week works well for me, as I'll be at home. See you then.")

    /** The window in each state, in `build/window`, to look at. */
    private val shots: Path = Path.of("build", "window").createDirectories()

    @AfterTest
    fun close() = scope.cancel()

    private fun model(
        vararg answers: Any,
        season: Season? = null,
        weather: Weather? = null,
    ): SlothModel {
        val model =
            ScriptedChatModel {
                answers.forEach { if (it is Bite) answers(it) else says(it.toString()) }
            }
        return SlothModel(home, sloth(model, home.pile, about = home::about), scope, today = { "2026-10-23" }, season = season, weather = weather)
    }

    private fun SlothModel.settle() =
        runBlocking {
            while (session.snapshot.value is GraphSnapshot.Restoring || session.snapshot.value is GraphSnapshot.Running) kotlinx.coroutines.delay(10)
        }

    /** Draws the whole window, and checks that it drew something. */
    private fun shoot(
        model: SlothModel,
        name: String,
        night: Boolean = false,
    ) {
        val scene = ImageComposeScene(1120, 760, Density(1f)) { SlothScreen(model, night) }
        try {
            scene.render(0)
            val image = scene.render(1_000_000_000)
            val png = assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
            shots.resolve("$name.png").writeBytes(png)
            assertTrue(png.size > 10_000, "the window draws a scene")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the window wakes it, shows the bite, and swallows the answer`() {
        home.pile.add("From my landlord: the yearly boiler check is due. Which morning suits you?")
        home.pile.add("Decide on the gym membership")
        home.learn("I work from home on Tuesdays and Thursdays")
        home.putOn(listOf(Item.SUNGLASSES, Item.COFFEE))
        val model = model(bite, "Ate a boiler check. It was warm, and nobody hurried.")
        model.settle()
        shoot(model, "1-asleep")

        model.wake()
        model.settle()
        val waiting = assertIs<GraphSnapshot.Interrupted<Sloth>>(model.session.snapshot.value)
        assertEquals(bite, waiting.state.bite)
        assertNotNull(home.awake(), "the lock is free while it waits for the answer").close()
        shoot(model, "2-bite")

        model.answer(Answer.YES)
        model.settle()
        val done = assertIs<GraphSnapshot.Completed<Sloth>>(model.session.snapshot.value)
        assertEquals(1, done.state.roundness)
        assertEquals(listOf("Decide on the gym membership"), model.pile)
        assertTrue(model.ateToday(done.state))
        shoot(model, "3-ate", night = true)
    }

    @Test
    fun `it waits while a terminal has it awake`() {
        home.pile.add("Decide on the gym membership")
        val model = model()
        model.settle()

        home.awake()!!.use { model.wake() }

        assertTrue(model.busy)
        assertIs<GraphSnapshot.Idle>(model.session.snapshot.value)
        model.refresh()
        assertTrue(!model.busy)
    }

    @Test
    fun `after the last item it sleeps on top of its branch, dressed, at night`() {
        home.pile.add("Decide on the gym membership")
        home.putOn(listOf(Item.BARRETINA, Item.COFFEE))
        home.settle(Place("Girona, Spain", 41.98, 2.82))
        val model = model(Bite(Kind.DECISION, "Cancel the gym", "Cancel it: you went twice in March."), "The pile is empty. I shall nap on top of my branch.")
        model.settle()
        model.wake()
        model.settle()
        model.answer(Answer.NO)
        model.settle()

        assertTrue(model.isFull(model.session.snapshot.value.state))
        assertNull(model.trouble)
        assertEquals(Weather.CLEAR, model.weather, "no weather service in the test: it keeps the clear sky")
        shoot(model, "4-full-night", night = true)
    }

    @Test
    fun `the habitat shows every season and every weather`() {
        home.pile.add("Decide on the gym membership")
        val seasons = listOf(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.AUTUMN, Season.WINTER, Season.SUMMER)
        Weather.entries.forEachIndexed { index, weather ->
            val season = seasons[index]
            val model = model(season = season, weather = weather)
            model.settle()
            assertEquals(season, model.season)
            shoot(model, "weather-${weather.name.lowercase()}-${season.name.lowercase()}", night = index % 3 == 2)
        }
    }

    @Test
    fun `the season comes from the date, and turns around south of the equator`() {
        assertEquals(Season.AUTUMN, season(java.time.LocalDate.parse("2026-10-23")))
        assertEquals(Season.SPRING, season(java.time.LocalDate.parse("2026-10-23"), southern = true))
        assertEquals(Season.WINTER, season(java.time.LocalDate.parse("2026-01-10")))
        assertEquals(Weather.RAIN, weather(61))
        assertEquals(Weather.SNOW, weather(73))
        assertEquals(Weather.STORM, weather(95))
        assertEquals(Weather.FOG, weather(45))
        assertEquals(Weather.CLEAR, weather(0))
    }
}
