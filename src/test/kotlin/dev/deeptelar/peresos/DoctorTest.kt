package dev.deeptelar.peresos

import dev.deeptelar.telar.test.ScriptedChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// runBlocking, not runTest: the pretend Ollama answers on a thread of its own, and the virtual time of runTest
// would let the time limits of the doctor run out at once.
class DoctorTest {
    private val home = Home(Files.createTempDirectory("peresos-doctor"))

    /** An Ollama that runs and has [models], until [pull] adds the model. */
    private fun ollama(
        models: List<String> = listOf("gemma4:12b"),
        running: Boolean = true,
    ): HttpClient {
        var pulled = models
        return HttpClient(
            MockEngine { request ->
                if (!running) throw ConnectException("Connection refused")
                when (request.url.encodedPath) {
                    "/api/version" -> respond("""{"version":"0.40.8"}""")
                    "/api/tags" -> respond("""{"models":[${pulled.joinToString { """{"name":"$it"}""" }}]}""")
                    "/api/pull" -> {
                        pulled = pulled + "gemma4:12b"
                        respond(
                            """
                            {"status":"pulling manifest"}
                            {"status":"pulling abc","total":1000000000,"completed":0}
                            {"status":"pulling abc","total":1000000000,"completed":500000000}
                            {"status":"pulling abc","total":1000000000,"completed":1000000000}
                            {"status":"success"}
                            """.trimIndent(),
                        )
                    }
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )
    }

    private fun machine(
        installed: Boolean = true,
        headless: Boolean = false,
        tray: Boolean = true,
        os: String = "Linux",
    ) = Machine(
        os = os,
        headless = { headless },
        tray = { tray },
        find = { command -> if (command == "ollama" && installed) Path.of("/usr/bin/ollama") else null },
        ollamaApps = emptyList(),
    )

    private fun doctor(
        client: HttpClient = ollama(),
        gemma: ScriptedChatModel = ScriptedChatModel { says("Ready.") },
        machine: Machine = machine(),
        said: MutableList<String> = mutableListOf(),
    ) = Doctor(home, client, gemma, ollama = Doctor.DEFAULT_OLLAMA, modelName = "gemma4:12b", machine = machine, say = { said += it })

    private fun List<Check>.of(name: String): Check = single { it.name == name }

    @Test
    fun `everything is there`() =
        runBlocking {
            val checks = doctor().examine()

            assertEquals(listOf(Verdict.OK, Verdict.OK, Verdict.OK, Verdict.OK, Verdict.OK), checks.dropLast(1).map { it.verdict })
            assertEquals("0.40.8 at http://127.0.0.1:11434", checks.of("Ollama").found)
            assertEquals("gemma4:12b is pulled", checks.of("Gemma").found)
            assertEquals(Verdict.WARN, checks.of("The command").verdict, "the tests do not install peresos")
        }

    @Test
    fun `without Ollama it says how to get it, and checks nothing that needs it`() =
        runBlocking {
            val notInstalled = doctor(ollama(running = false), machine = machine(installed = false)).examine()
            val stopped = doctor(ollama(running = false)).examine()
            val stoppedOnWindows = doctor(ollama(running = false), machine = machine(os = "Windows 11")).examine()

            assertEquals(Check("Ollama", Verdict.FAIL, "not installed", "Install it from https://ollama.com/download, then run peresos doctor --fix."), notInstalled.of("Ollama"))
            assertEquals("Start it: ollama serve (or sudo systemctl start ollama)", stopped.of("Ollama").fix)
            assertEquals("Open the Ollama app, or run: ollama serve", stoppedOnWindows.of("Ollama").fix)
            assertFalse(stopped.any { it.name.startsWith("Gemma") })
            assertEquals(listOf("Its home", "Ollama", "Its window", "The command"), stopped.map { it.name })
        }

    @Test
    fun `a model that is not pulled fails, and --fix pulls it`() =
        runBlocking {
            val said = mutableListOf<String>()
            val missing = doctor(ollama(models = listOf("llama3:8b"))).examine()
            val fixed = doctor(ollama(models = emptyList()), said = said).examine(fix = true)

            assertEquals(Check("Gemma", Verdict.FAIL, "gemma4:12b is not pulled", "Pull it: peresos doctor --fix, or ollama pull gemma4:12b"), missing.of("Gemma"))
            assertFalse(missing.any { it.name == "Gemma answers" })
            assertEquals(Check("Gemma", Verdict.OK, "gemma4:12b is pulled, just now"), fixed.of("Gemma"))
            assertEquals(Verdict.OK, fixed.of("Gemma answers").verdict)
            assertEquals(listOf("pulling gemma4:12b: 0%", "pulling gemma4:12b: 50%", "pulling gemma4:12b: 100%"), said.filter { "pulling" in it }.map { it.trim() })
        }

    @Test
    fun `a model without a tag is the latest`() =
        runBlocking {
            val checks = Doctor(home, ollama(models = listOf("gemma4:latest")), ScriptedChatModel { says("Ready.") }, Doctor.DEFAULT_OLLAMA, "gemma4", machine()).examine()

            assertEquals(Verdict.OK, checks.of("Gemma").verdict)
        }

    @Test
    fun `a Gemma that cannot answer fails with Ollama's reason`() =
        runBlocking {
            val checks = doctor(gemma = ScriptedChatModel { fails("model requires more system memory (9.1 GiB) than is available (6.0 GiB)") }).examine()

            assertEquals(Verdict.FAIL, checks.of("Gemma answers").verdict)
            assertContains(checks.of("Gemma answers").found, "more system memory")
        }

    @Test
    fun `the window wants a desktop and a tray, but the terminal does not`() =
        runBlocking {
            assertEquals(Verdict.WARN, doctor(machine = machine(headless = true)).examine().of("Its window").verdict)
            assertEquals(Verdict.WARN, doctor(machine = machine(tray = false)).examine().of("Its window").verdict)
        }

    @Test
    fun `the report has the checks and the log, and nothing of the pile`() =
        runBlocking {
            home.pile.add("Call the bank about the mortgage")
            home.learn("I live in Girona")
            home.log.error("peresos", IllegalStateException("It broke"))
            val doctor = doctor()
            val checks = doctor.examine()

            val report = doctor.report(checks)

            assertContains(report, "[ok] Ollama         0.40.8 at http://127.0.0.1:11434")
            assertContains(report, "java.lang.IllegalStateException: It broke")
            assertFalse("mortgage" in report || "Girona" in report)
            assertTrue(doctor.save(report).toFile().readText() == report)
        }

    @Test
    fun `the issue keeps the end of a long log and fits a browser`() =
        runBlocking {
            repeat(100) { home.log.error("wake $it", IllegalStateException("Failure $it")) }
            val doctor = doctor()

            val url = doctor.issue(doctor.examine())

            assertTrue(url.length <= 7_000, "${url.length} characters")
            assertContains(URLDecoder.decode(url, Charsets.UTF_8), "Failure 99")
        }

    @Test
    fun `OLLAMA_HOST is read the way Ollama reads it`() {
        assertEquals("http://127.0.0.1:11434", ollamaUrl(null))
        assertEquals("http://127.0.0.1:11434", ollamaUrl(" "))
        assertEquals("http://127.0.0.1:11434", ollamaUrl("0.0.0.0"))
        assertEquals("http://127.0.0.1:8080", ollamaUrl("0.0.0.0:8080"))
        assertEquals("http://gpu-box:11434", ollamaUrl("gpu-box"))
        assertEquals("https://ollama.example.com:443", ollamaUrl("https://ollama.example.com/"))
        assertEquals("http://192.168.1.5:80", ollamaUrl("http://192.168.1.5"))
    }

    @Test
    fun `the log keeps what went wrong`() {
        home.log.error("peresos window", IllegalStateException("The tray is gone"))

        home.log.error("peresos", IllegalStateException("Gemma is gone"))

        val last = home.log.last(1, lines = 2)
        assertContains(last.first(), "peresos dev: peresos")
        assertEquals("java.lang.IllegalStateException: Gemma is gone", last[1])
        assertEquals(2, last.size)
        assertContains(home.log.last(2).first(), "peresos window")
    }

    @Test
    fun `the person reads why it stopped, not a stack trace`() {
        assertEquals(
            "Peresós could not reach Gemma. Is Ollama running? peresos doctor says what is missing.",
            trouble(IllegalStateException("Node 'chew' failed", ConnectException("Connection refused"))),
        )
        assertEquals("Ollama has no ${Home.modelName} yet. peresos doctor --fix pulls it.", trouble(IllegalStateException("model '${Home.modelName}' not found")))
        assertEquals("Something went wrong: The disk is full", trouble(IllegalStateException("Node 'swallow' failed", java.io.IOException("The disk is full"))))
    }
}
