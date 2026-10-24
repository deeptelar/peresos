package dev.deeptelar.peresos

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

/**
 * `peresos update`, and Update on the About page of its window: it asks GitHub for the latest
 * release, and installs it with the installer of the README, the way Peresós was installed. It
 * reaches GitHub only when the person asks for it, and sends nothing about them.
 *
 * @param from where the installer takes the files: `PERESOS_FROM`, a folder or an address, to try a
 *   build before a release. Then it installs that build, whatever its version.
 * @param current the version that runs, as `1.0.2`.
 */
internal class Updater(
    private val client: HttpClient,
    val from: String? = System.getenv("PERESOS_FROM")?.takeIf { it.isNotBlank() },
    val current: String = VERSION,
) {
    /** The version of the latest release, as `1.0.3`, or `null` when [from] is a build to try. */
    suspend fun latest(): String? {
        if (from != null) return null
        val response = client.get(LATEST)
        if (!response.status.isSuccess()) throw IOException("GitHub answered ${response.status}.")
        val tag = Json.parseToJsonElement(response.bodyAsText()).jsonObject["tag_name"]?.jsonPrimitive?.content ?: throw IOException("GitHub named no release.")
        return tag.removePrefix("v")
    }

    /**
     * Installs the latest release over this one. In a [terminal] the installer writes there, and apt
     * asks for the password with sudo; from the window, with pkexec, and the doctor does not run.
     * On Windows, from the window, PowerShell installs it once Peresós quits, then opens it again.
     *
     * @throws IOException with what the person can do instead, when it cannot.
     */
    suspend fun update(
        installed: Installed,
        terminal: Boolean,
    ) {
        when (installed) {
            is Installed.Windows -> {
                if (terminal) throw IOException("On Windows, Peresós cannot replace itself while this command runs. Run the installer again, in PowerShell: $WINDOWS_ONE_LINE")
                val script = windowsUpdateScript(installed.folder, from, ProcessHandle.current().pid())
                ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
                    .fresh()
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            is Installed.Apt, is Installed.Files -> {
                val installer = Files.createTempFile("peresos-install", ".sh")
                try {
                    installer.writeBytes(installScript())
                    val command = ProcessBuilder("sh", installer.toString()).fresh()
                    val env = command.environment()
                    // The installer puts it back where it was: in the home, or with apt.
                    if (installed is Installed.Files) env["PERESOS_LOCAL"] = "1"
                    if (!terminal) {
                        env["PERESOS_SUDO"] = "pkexec"
                        env["PERESOS_NO_DOCTOR"] = "1"
                    }
                    if (terminal) command.inheritIO() else command.redirectErrorStream(true)
                    val process = command.start()
                    val output = if (terminal) "" else process.inputStream.readAllBytes().decodeToString()
                    if (process.waitFor() != 0) throw IOException("The installer stopped. ${output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()} Run it in a terminal: $ONE_LINE".replace("  ", " "))
                } finally {
                    Files.deleteIfExists(installer)
                }
            }
            is Installed.Source -> throw IOException("Peresós runs from its source here: update it with git pull.")
        }
    }

    /** install.sh: from [from] when it is a folder that has one, as in CI; else the one on GitHub. */
    private suspend fun installScript(): ByteArray {
        from?.let { Path.of(it).resolve("install.sh") }?.takeIf { it.exists() }?.let { return Files.readAllBytes(it) }
        val response = client.get(INSTALL_SH)
        if (!response.status.isSuccess()) throw IOException("GitHub answered ${response.status} for the installer.")
        return response.bodyAsBytes()
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/deeptelar/peresos/releases/latest"
        private const val INSTALL_SH = "https://raw.githubusercontent.com/deeptelar/peresos/main/install.sh"
        const val RELEASES = "https://github.com/deeptelar/peresos/releases/latest"
        const val ONE_LINE = "curl -fsSL $INSTALL_SH | sh"
        const val WINDOWS_ONE_LINE = "irm https://raw.githubusercontent.com/deeptelar/peresos/main/install.ps1 | iex"
    }
}

/** Whether [latest] is a newer version than [current], both as `1.0.3`. A build from the source, `dev`, is never updated. */
internal fun isNewer(
    latest: String,
    current: String,
): Boolean {
    fun parts(version: String) = version.split('.', '-').map { it.toIntOrNull() }
    val new = parts(latest)
    val now = parts(current)
    if (null in new || null in now) return false
    return new.zip(now).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a!! > b!! } ?: (new.size > now.size)
}

/**
 * The PowerShell that updates Peresós on Windows once the window, the process [pid], quits: it runs
 * install.ps1 from GitHub, or from [from] when that is a folder, then opens the window again.
 */
internal fun windowsUpdateScript(
    folder: Path,
    from: String?,
    pid: Long,
): String {
    fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
    return buildString {
        appendLine("Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue")
        appendLine("\$env:PERESOS_NO_DOCTOR = '1'")
        if (from != null) appendLine("\$env:PERESOS_FROM = ${quoted(from)}")
        val local = from?.let { Path.of(it).resolve("install.ps1") }?.takeIf { it.exists() }
        appendLine(if (local != null) "& ${quoted(local.toString())}" else "irm https://raw.githubusercontent.com/deeptelar/peresos/main/install.ps1 | iex")
        appendLine("Start-Process -FilePath ${quoted(folder.resolve("peresos-window.exe").toString())}")
    }
}

/**
 * This process, without what the launcher of jpackage set for this one: for a process that may
 * start Peresós again. A launcher that finds `_JPACKAGE_LAUNCHER` takes its arguments for the JVM's,
 * so `peresos doctor` would look for a class called doctor.
 */
internal fun ProcessBuilder.fresh(): ProcessBuilder =
    apply {
        val env = environment()
        env.remove("_JPACKAGE_LAUNCHER")
        val app = System.getProperty("jpackage.app-path")?.let { Path.of(it).toAbsolutePath().parent?.parent?.toString() }
        val libraries = env["LD_LIBRARY_PATH"]?.split(':')?.filter { it.isNotEmpty() && (app == null || !it.startsWith(app)) }
        if (libraries.isNullOrEmpty()) env.remove("LD_LIBRARY_PATH") else env["LD_LIBRARY_PATH"] = libraries.joinToString(":")
    }
