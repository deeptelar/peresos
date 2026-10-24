package dev.deeptelar.peresos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.test.ScriptedChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        installed: Installed? = null,
        updater: Updater? = null,
    ): SlothModel {
        val model =
            ScriptedChatModel {
                answers.forEach { if (it is Bite) answers(it) else says(it.toString()) }
            }
        return SlothModel(home, sloth(model, home.pile, about = home::about), scope, today = { "2026-10-23" }, season = season, weather = weather, installed = installed, updater = updater)
    }

    private fun SlothModel.settle() =
        runBlocking {
            while (session.snapshot.value is GraphSnapshot.Restoring || session.snapshot.value is GraphSnapshot.Running) kotlinx.coroutines.delay(10)
            // The window reads the pile again after a run, a moment after the snapshot says it ended.
            while (pile != home.pile.items()) kotlinx.coroutines.delay(10)
        }

    /** Draws the whole window, and checks that it drew something. */
    private fun shoot(
        model: SlothModel,
        name: String,
        night: Boolean = false,
    ) {
        val scene = ImageComposeScene(WINDOW_SIZE.width.value.toInt(), WINDOW_SIZE.height.value.toInt(), Density(1f)) { SlothScreen(model, night) }
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the menu opens the other pages, the about page, and quits`() {
        home.pile.add("Decide on the gym membership")
        val model = model()
        model.settle()
        var quit = false
        runComposeUiTest {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model, onQuit = { quit = true }) } }
            onNodeWithText("Wake it").assertExists()

            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("menu.png").toFile())
            onNodeWithText("Pile 1").performClick()
            waitForIdle()
            onNodeWithText("Decide on the gym membership").assertExists()
            onNodeWithText("Wake it").assertDoesNotExist()

            onNodeWithText("Back").performClick()
            waitForIdle()
            onNodeWithText("Wake it").assertExists()

            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            onNodeWithText("About").performClick()
            waitForIdle()
            onNodeWithText("github.com/deeptelar/peresos").assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("about.png").toFile())
            onNodeWithText("Back").performClick()
            waitForIdle()

            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            onNodeWithText("Quit").performClick()
            waitForIdle()
            assertTrue(quit)
            onNodeWithText("Pile 1").assertDoesNotExist()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the about page uninstalls it, after asking, and forgets the pile when told`() {
        home.pile.add("Decide on the gym membership")
        // What install.sh puts in a home on Linux: the longest list it shows.
        val user = Files.createTempDirectory("ana")
        val installed = listOf(".local/share/peresos", ".local/bin/peresos", ".local/share/applications/peresos.desktop").map { user.resolve(it).createDirectories() }
        val model = model(installed = Installed.Files(installed))
        model.settle()
        var quit = false
        runComposeUiTest {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model, onQuit = { quit = true }) } }
            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            onNodeWithText("About").performClick()
            waitForIdle()

            onNodeWithText("Uninstall").performClick()
            waitForIdle()
            onNodeWithText("Uninstall?").assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("uninstall.png").toFile())
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onNodeWithText("Uninstall?").assertDoesNotExist()
            assertTrue(installed.all { it.exists() })

            onNodeWithText("Uninstall").performClick()
            waitForIdle()
            onNodeWithText("Forget my pile too").performClick()
            onNodeWithText("Uninstall").performClick()
            waitUntil(timeoutMillis = 5_000) { quit }
            assertTrue(installed.none { it.exists() })
            assertFalse(home.path.exists())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the pile page feeds a photo the person chose`() {
        val photo = Files.createTempDirectory("peresos-photos").resolve("receipt.png")
        ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", photo.toFile())
        val model = model()
        model.settle()
        runComposeUiTest {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model, chooseFiles = { listOf(photo, photo.resolveSibling("song.mp3")) }) } }
            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            onNodeWithText("Pile 0").performClick()
            waitForIdle()
            onNodeWithText("Or feed a photo, a PDF or a text file").performClick()
            waitForIdle()
            onNodeWithText("Photo: receipt.png").assertExists()
            onNodeWithText("Peresós eats photos", substring = true).assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("pile-photo.png").toFile())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the about page asks GitHub for a newer version, when told`() {
        val github = HttpClient(MockEngine { respond("""{"tag_name":"v9.0.0"}""") })
        val model = model(installed = Installed.Apt, updater = Updater(github, from = null, current = "1.0.2"))
        model.settle()
        runComposeUiTest {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model) } }
            onNodeWithContentDescription("Menu").performClick()
            waitForIdle()
            onNodeWithText("About").performClick()
            waitForIdle()
            assertNull(model.update, "nothing is asked before the person clicks")
            onNodeWithText("Check for updates").performClick()
            waitUntil(timeoutMillis = 5_000) { model.update.let { it != null && it != Update.Checking } }
            assertIs<Update.Available>(model.update, model.update.toString())
            onNodeWithText("Peresós 9.0.0 is out.").assertExists()
            onNodeWithText("Update").assertExists()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("update.png").toFile())
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `touched, it reacts to a pat, a tickle and a poke, until it is fed up`() {
        home.pile.add("Decide on the gym membership")
        val model = model()
        model.settle()
        runComposeUiTest {
            setContent { Box(Modifier.size(WINDOW_SIZE)) { SlothScreen(model) } }
            val still = Portrait.pictures(0).first()
            val stage = stage(WINDOW_SIZE.width.value * density.density, HABITAT_HEIGHT.value * density.density, still, onTop = false)

            fun touch(
                part: Touch,
                says: String,
                shot: String? = null,
            ) {
                onRoot().performTouchInput { click(stage.where(still, part)) }
                waitForIdle()
                onNodeWithText(says).assertExists()
                shot?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("$it.png").toFile()) }
            }
            touch(Touch.HEAD, "Mmm. Behind the ears, please.", "touch-1-pat")
            touch(Touch.BELLY, "That tickles! Slowly, but it tickles.", "touch-2-tickle")
            touch(Touch.FUR, "Wake me with the button. Gently.", "touch-3-poke")
            touch(Touch.FUR, "Mmh? Five more minutes.")
            touch(Touch.FUR, "1 on the pile. None of them on fire.")
            touch(Touch.FUR, "Zzz. (It pretends to sleep.)", "touch-6-fed-up")
        }
        assertNull(stage(400f, 170f, Portrait.pictures(0).first(), onTop = false).part(Portrait.pictures(0).first(), 2f, 2f), "the sky is not the sloth")
        // Every size of it, hanging or on top and dressed, has a head and fur to touch, and its reaction fits its place.
        for (roundness in listOf(0, 3, 7, 12)) {
            for (onTop in listOf(false, true)) {
                val still = Portrait.pictures(roundness, onTop, listOf(Item.BARRETINA, Item.COFFEE)).first()
                val parts = still.pixels.flatMap { line -> line.map(Portrait::part) }.toSet()
                assertTrue(Touch.HEAD in parts && Touch.FUR in parts, "roundness $roundness, on top $onTop: $parts")
                for (touch in Touch.entries) {
                    val touched = Portrait.touched(roundness, onTop, listOf(Item.BARRETINA, Item.COFFEE), touch)
                    assertEquals(still.width to still.height, touched.width to touched.height)
                    assertEquals(still.branch, touched.branch)
                }
            }
        }
    }

    /** Where on the screen to touch [part] of [picture]: its pixel nearest to the middle of the picture. */
    private fun Stage.where(
        picture: Picture,
        part: Touch,
    ): Offset {
        val pixels = picture.pixels.flatMapIndexed { y, line -> line.indices.filter { Portrait.part(line[it]) == part }.map { it to y } }
        val (x, y) = pixels.minBy { (x, y) -> (x - picture.width / 2) * (x - picture.width / 2) + (y - picture.height / 2) * (y - picture.height / 2) }
        return Offset((left + x + 0.5f) * scale, (top + y + 0.5f) * scale)
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
