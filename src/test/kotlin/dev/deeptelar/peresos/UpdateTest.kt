package dev.deeptelar.peresos

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private fun github(tag: String? = "v1.0.3") =
        HttpClient(
            MockEngine { request ->
                when {
                    tag == null -> respondError(HttpStatusCode.ServiceUnavailable)
                    request.url.host == "api.github.com" -> respond("""{"tag_name":"$tag","name":"Peresós $tag"}""")
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )

    @Test
    fun `a version is newer by its numbers, not by its text`() {
        assertTrue(isNewer("1.0.3", "1.0.2"))
        assertTrue(isNewer("1.0.10", "1.0.9"))
        assertTrue(isNewer("2.0.0", "1.9.9"))
        assertTrue(isNewer("1.1", "1.0.9"))
        assertFalse(isNewer("1.0.2", "1.0.2"))
        assertFalse(isNewer("1.0.1", "1.0.2"))
        assertFalse(isNewer("1.0.3", "dev"), "a build from the source is never updated")
    }

    @Test
    fun `a process that may start Peresós again gets none of what its launcher set`() {
        val builder = ProcessBuilder("true").apply { environment()["_JPACKAGE_LAUNCHER"] = "0" }.fresh()
        assertFalse("_JPACKAGE_LAUNCHER" in builder.environment())
    }

    @Test
    fun `it reads the version of the latest release from GitHub`() =
        runBlocking<Unit> {
            assertEquals("1.0.3", Updater(github(), from = null).latest())
            assertFailsWith<IOException> { Updater(github(tag = null), from = null).latest() }
        }

    @Test
    fun `a build to try has no version to compare`() =
        runBlocking<Unit> {
            assertNull(Updater(github(), from = "/tmp/dist").latest())
        }

    @Test
    fun `from the window it runs the installer in the home, with pkexec and without the doctor`() =
        runBlocking<Unit> {
            val dist = Files.createTempDirectory("peresos-dist")
            val seen = dist.resolve("seen")
            // An installer that writes down what it was given, in place of the real one.
            dist.resolve("install.sh").writeText("printf '%s %s %s %s' \"\$PERESOS_LOCAL\" \"\$PERESOS_SUDO\" \"\$PERESOS_NO_DOCTOR\" \"\$PERESOS_FROM\" > '$seen'\n")
            Updater(github(), from = dist.toString()).update(Installed.Files(emptyList()), terminal = false)
            // PERESOS_FROM is the one of this process, which the installer inherits: unset in a test.
            assertEquals("1 pkexec 1 ${System.getenv("PERESOS_FROM").orEmpty()}", seen.readText())
        }

    @Test
    fun `a failed installer says what to do instead`() =
        runBlocking<Unit> {
            val dist = Files.createTempDirectory("peresos-dist")
            dist.resolve("install.sh").writeText("echo 'Could not download the package.'\nexit 1\n")
            val failure = assertFailsWith<IOException> { Updater(github(), from = dist.toString()).update(Installed.Apt(), terminal = false) }
            assertContains(failure.message!!, "Could not download the package.")
            assertContains(failure.message!!, Updater.ONE_LINE)
        }

    @Test
    fun `on Windows the terminal is told to run the installer again`() =
        runBlocking<Unit> {
            val failure = assertFailsWith<IOException> { Updater(github(), from = null).update(Installed.Windows(Path.of("/c/Peresos")), terminal = true) }
            assertContains(failure.message!!, Updater.WINDOWS_ONE_LINE)
        }

    @Test
    fun `the script of Windows waits for the window, installs, and opens it again`() {
        val script = windowsUpdateScript(Path.of("/c/Users/ana/Programs/Peresos"), from = null, pid = 4242)
        val lines = script.trim().lines()
        assertEquals("Wait-Process -Id 4242 -Timeout 60 -ErrorAction SilentlyContinue", lines.first())
        assertContains(script, "\$env:PERESOS_NO_DOCTOR = '1'")
        assertContains(script, "install.ps1 | iex")
        assertEquals("Start-Process -FilePath '/c/Users/ana/Programs/Peresos/peresos-window.exe'", lines.last())
    }
}
