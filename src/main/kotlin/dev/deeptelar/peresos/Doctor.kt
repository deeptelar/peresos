package dev.deeptelar.peresos

import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.chat
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.URLEncoder
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** How a check of the doctor went: fine, worth knowing, or in the way. */
enum class Verdict(
    val mark: String,
) {
    OK("[ok]"),
    WARN("[!!]"),
    FAIL("[no]"),
}

/** One line of `peresos doctor`: what it checked, what it found and, when something is missing, how to fix it. */
data class Check(
    val name: String,
    val verdict: Verdict,
    val found: String,
    val fix: String? = null,
)

/** What the doctor asks of the computer, so a test can be another computer. */
internal class Machine(
    val os: String = System.getProperty("os.name"),
    val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
    val tray: () -> Boolean = { SystemTray.isSupported() },
    val find: (String) -> Path? = ::onPath,
    /** Where Ollama installs its app when it is not on the `PATH`, as on Windows and macOS. */
    val ollamaApps: List<Path> =
        listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", "Ollama", "ollama.exe") },
            Path.of("/Applications/Ollama.app"),
        ),
) {
    val windows: Boolean get() = os.startsWith("Windows")
    val linux: Boolean get() = os == "Linux"
}

/** The file that runs [command] from the `PATH`, or `null`. */
internal fun onPath(command: String): Path? {
    val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("$command.exe", "$command.cmd", "$command.bat") else listOf(command)
    return System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .flatMap { folder -> names.mapNotNull { runCatching { Path.of(folder, it) }.getOrNull() } }
        .firstOrNull { it.isRegularFile() && it.isExecutable() }
}

/** Peresós, its version and where it runs: the first line of the doctor and of its report. */
internal fun whereItRuns(): String {
    val java = "Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
    val launcher = System.getProperty("jpackage.app-path")?.let { "installed at $it" } ?: "run from its source"
    return "Peresós $VERSION on ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}), $java, $launcher"
}

/**
 * `peresos doctor`: checks one by one what Peresós needs, and says how to get what is missing. Its
 * home must be writable, Ollama must run, Gemma must be pulled and must answer; the window wants a
 * desktop and a tray, and the terminal the `peresos` command on the `PATH`.
 *
 * @param gemma the model it asks for a word, to see that Gemma loads and answers.
 * @param say what it prints while it waits: pulling Gemma, or loading it.
 */
internal class Doctor(
    private val home: Home,
    private val client: HttpClient,
    private val gemma: ChatModel,
    private val ollama: String = Home.ollama,
    private val modelName: String = Home.modelName,
    private val machine: Machine = Machine(),
    private val say: (String) -> Unit = ::println,
) {
    /** Runs every check, and gives each to [found] as soon as it is done. With [fix], it pulls Gemma when it is missing. */
    suspend fun examine(
        fix: Boolean = false,
        found: (Check) -> Unit = {},
    ): List<Check> {
        val checks = mutableListOf<Check>()
        val add = { check: Check -> (check.verdict == Verdict.OK).also { checks += check.also(found) } }
        add(home())
        // Each needs the one before: no models without Ollama, no answer without the model.
        if (add(ollama()) && add(pulled(fix))) add(answers())
        add(window())
        add(command())
        return checks
    }

    private fun home(): Check =
        try {
            home.path.createDirectories()
            home.path.resolve(".doctor").apply { writeText("ok") }.deleteIfExists()
            Check("Its home", Verdict.OK, home.shown)
        } catch (e: IOException) {
            Check("Its home", Verdict.FAIL, "${home.shown} cannot be written: ${e.message}", "Point PERESOS_HOME to a folder you can write to.")
        }

    private suspend fun ollama(): Check {
        val version = reach { json("/api/version")["version"]?.jsonPrimitive?.content ?: "?" }
        if (version.isSuccess) return Check("Ollama", Verdict.OK, "${version.getOrThrow()} at $ollama")
        val local = ollama == DEFAULT_OLLAMA
        val installed = machine.find("ollama") != null || machine.ollamaApps.any { it.exists() }
        return when {
            !local -> Check("Ollama", Verdict.FAIL, "not running at $ollama (OLLAMA_HOST)", "Start it there, or unset OLLAMA_HOST to use the Ollama of this computer.")
            !installed -> Check("Ollama", Verdict.FAIL, "not installed", "Install it from https://ollama.com/download, then run peresos doctor --fix.")
            machine.linux -> Check("Ollama", Verdict.FAIL, "installed, but not running", "Start it: ollama serve (or sudo systemctl start ollama)")
            else -> Check("Ollama", Verdict.FAIL, "installed, but not running", "Open the Ollama app, or run: ollama serve")
        }
    }

    private suspend fun pulled(fix: Boolean): Check {
        val models = reach { models() }.getOrElse { return Check("Gemma", Verdict.FAIL, "Ollama did not list its models: ${it.message}") }
        if (models.has()) return Check("Gemma", Verdict.OK, "$modelName is pulled")
        if (!fix) return Check("Gemma", Verdict.FAIL, "$modelName is not pulled", "Pull it: peresos doctor --fix, or ollama pull $modelName")
        reach { pull() }.onFailure { return Check("Gemma", Verdict.FAIL, "pulling $modelName failed: ${it.message}", "Try it with Ollama itself: ollama pull $modelName") }
        return if (reach { models() }.getOrDefault(emptyList()).has()) {
            Check("Gemma", Verdict.OK, "$modelName is pulled, just now")
        } else {
            Check("Gemma", Verdict.FAIL, "$modelName is still not there after the pull", "Try it with Ollama itself: ollama pull $modelName")
        }
    }

    private suspend fun answers(): Check {
        say("       Asking Gemma for one word. The first answer loads it, which can take a minute.")
        val (answer, took) = measureTimedValue { reach { withTimeoutOrNull(5.minutes) { gemma.chat("Answer with one word: ready") } } }
        return answer.fold(
            onSuccess = { text ->
                if (text == null) {
                    Check("Gemma answers", Verdict.FAIL, "no answer in 5 minutes", "Your computer may be short of memory for $modelName: try a smaller model with PERESOS_MODEL.")
                } else {
                    Check("Gemma answers", Verdict.OK, "in %.1f s".format(took.inWholeMilliseconds / 1000.0))
                }
            },
            onFailure = { Check("Gemma answers", Verdict.FAIL, "it failed: ${it.message?.take(300)}", "If it needs more memory than you have, try a smaller model with PERESOS_MODEL.") },
        )
    }

    private fun window(): Check =
        when {
            machine.headless() -> Check("Its window", Verdict.WARN, "no desktop here", "The terminal works; peresos window needs a desktop.")
            !machine.tray() -> Check("Its window", Verdict.WARN, "a desktop without a tray: the window has a frame, and closing it quits")
            else -> Check("Its window", Verdict.OK, "a desktop with a tray")
        }

    private fun command(): Check {
        machine.find("peresos")?.let { return Check("The command", Verdict.OK, "peresos runs from any terminal ($it)") }
        val launcher = System.getProperty("jpackage.app-path")?.let { Path.of(it).parent }
        val fix = launcher?.let { "Add $it to your PATH, or install it again: https://github.com/deeptelar/peresos#install" } ?: "Install it: https://github.com/deeptelar/peresos#install"
        return Check("The command", Verdict.WARN, "peresos is not on your PATH", fix)
    }

    private suspend fun json(path: String): JsonObject =
        withTimeoutOrNull(10.seconds) {
            val response = client.get("$ollama$path")
            check(response.status.isSuccess()) { "Ollama answered ${response.status}: ${response.bodyAsText()}" }
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
        } ?: throw ConnectException("Ollama did not answer at $ollama in 10 seconds")

    private suspend fun models(): List<String> = json("/api/tags")["models"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty()

    /** Ollama names a model without a tag `name:latest`. */
    private fun List<String>.has(): Boolean = modelName in this || (":" !in modelName && "$modelName:latest" in this)

    /** Pulls Gemma, and says how far it got every tenth of the way. */
    private suspend fun pull() {
        val body = buildJsonObject { put("model", modelName) }.toString()
        client.preparePost("$ollama/api/pull") { setBody(TextContent(body, ContentType.Application.Json)) }.execute { response ->
            check(response.status.isSuccess()) { "Ollama answered ${response.status}: ${response.bodyAsText()}" }
            val lines = response.bodyAsChannel()
            var said = ""
            while (true) {
                val line = lines.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val update = Json.parseToJsonElement(line).jsonObject
                update["error"]?.jsonPrimitive?.content?.let { error(it) }
                val total = update["total"]?.jsonPrimitive?.longOrNull
                val done = update["completed"]?.jsonPrimitive?.longOrNull ?: 0
                val news = if (total != null && total > 100_000_000) "pulling $modelName: ${done * 10 / total * 10}%" else continue
                if (news != said) say("       $news").also { said = news }
            }
        }
    }

    /** Runs [block], and makes a failure a value, except a cancellation, which must reach the caller. */
    private suspend fun <T> reach(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** A file to attach to an issue: the system, the checks and the end of the log. Nothing of the pile, the diary or what it knows about you. */
    fun report(
        checks: List<Check>,
        failures: Int = 5,
        lines: Int = 60,
    ): String =
        buildString {
            appendLine("## Peresós doctor report")
            appendLine()
            appendLine(whereItRuns())
            appendLine()
            appendLine("PERESOS_MODEL=${System.getenv("PERESOS_MODEL").orEmpty()}, PERESOS_HOME=${System.getenv("PERESOS_HOME").orEmpty()}, OLLAMA_HOST=${System.getenv("OLLAMA_HOST").orEmpty()}")
            appendLine()
            appendLine("```")
            checks.forEach { appendLine(it.line()) }
            appendLine("```")
            val log = home.log.last(failures, lines)
            appendLine()
            appendLine("The last failures in its log (${home.log.file.toString().replaceFirst(System.getProperty("user.home"), "~")}):")
            appendLine()
            appendLine("```")
            if (log.isEmpty()) appendLine("(empty: nothing has gone wrong yet)") else log.forEach { appendLine(it) }
            appendLine("```")
        }

    /** Writes [report] in the folder of the log, and returns the file. */
    fun save(report: String): Path {
        val folder = home.path.resolve("logs").createDirectories()
        return folder.resolve("doctor-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.md").also { it.writeText(report) }
    }

    /**
     * The page of a new issue on GitHub with the report of [checks] filled in. It holds as many of
     * the last failures as a browser's address bar takes, the newest first; the file of the report has more.
     */
    fun issue(checks: List<Check>): String {
        val urls = listOf(3 to 25, 2 to 20, 1 to 20, 1 to 10, 1 to 3, 0 to 0).map { (failures, lines) -> "$ISSUES?title=${encode("Peresós: ")}&body=${encode(report(checks, failures, lines))}" }
        return urls.firstOrNull { it.length <= MAX_URL } ?: urls.last()
    }

    companion object {
        const val DEFAULT_OLLAMA = "http://127.0.0.1:11434"
        private const val MAX_URL = 7_000
        private const val ISSUES = "https://github.com/deeptelar/peresos/issues/new"

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    }
}

/** A check as the doctor prints it. */
internal fun Check.line(): String = "${verdict.mark} ${name.padEnd(14)} $found" + (fix?.let { "\n     ${" ".repeat(14)} $it" } ?: "")
