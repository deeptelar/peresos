package dev.deeptelar.peresos

import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText

/**
 * How Peresós came to this computer, read from where its launcher is, and so how it leaves: with
 * `peresos uninstall`, or Uninstall on the About page of its window. Ollama and Gemma stay, since
 * other apps may use them.
 */
internal sealed interface Installed {
    /** What leaves, as the person reads it before saying yes. */
    val shown: List<String>

    /** The .deb, in /opt/peresos: apt removes it, as root, and Peresós the [menus] of the file managers in the home. */
    data class Apt(
        val menus: List<Path> = emptyList(),
    ) : Installed {
        override val shown = listOf("the package peresos, with apt, which asks for your password (sudo apt remove peresos)") + menus.map { it.toString().replaceFirst(System.getProperty("user.home"), "~") }
    }

    /** What install.sh put in the home on Linux, or the app of macOS: these files and folders. */
    data class Files(
        val paths: List<Path>,
    ) : Installed {
        override val shown = paths.map { it.toString().replaceFirst(System.getProperty("user.home"), "~") }
    }

    /**
     * What install.ps1 put in [folder], its line in the PATH and its shortcut in the Start menu.
     * Windows keeps a running program from being deleted, so PowerShell does it once Peresós quits.
     */
    data class Windows(
        val folder: Path,
    ) : Installed {
        override val shown = listOf(folder.toString(), "its shortcut in the Start menu", "its folder in your PATH", "Feed to Peresós in the menu of files")
    }

    /** Run with Gradle from its source: nothing is installed. */
    data object Source : Installed {
        override val shown = emptyList<String>()
    }
}

/**
 * How Peresós was installed, from the path of its [launcher], or `null` when it is in a place no
 * installer of Peresós uses: then it removes nothing, rather than a folder it does not know.
 */
internal fun installed(
    launcher: String? = System.getProperty("jpackage.app-path"),
    os: String = System.getProperty("os.name"),
    userHome: Path = Path.of(System.getProperty("user.home")),
): Installed? {
    // Where it really is, when it was started from a link like ~/.local/bin/peresos.
    val path = Path.of(launcher ?: return Installed.Source).let { runCatching { it.toRealPath() }.getOrDefault(it.toAbsolutePath().normalize()) }
    return when {
        // peresos.exe and peresos-window.exe are at the top of its folder.
        os.startsWith("Windows") -> path.parent?.takeIf { it.named("peresos") }?.let { Installed.Windows(it) }
        // Peresos.app/Contents/MacOS/Peresos
        os.startsWith("Mac") -> path.parent?.parent?.parent?.takeIf { it.named("Peresos.app") }?.let { app -> Installed.Files(listOf(app) + linkTo(app, userHome)) }
        path.startsWith("/opt/peresos") -> Installed.Apt(menus(userHome))
        // peresos/bin/peresos, with a menu entry that opens it.
        else ->
            path.parent?.parent?.takeIf { it.named("peresos") }?.let { app ->
                val entry = userHome.resolve(".local/share/applications/peresos.desktop").takeIf { it.exists() && "$app/" in it.readText() }
                Installed.Files(listOf(app) + linkTo(app, userHome) + listOfNotNull(entry) + menus(userHome))
            }
    }
}

/** Feed to Peresós in the menus of the file managers of Linux, as install.sh writes them, where they are. */
internal fun menus(userHome: Path): List<Path> =
    listOf(
        ".local/share/nautilus/scripts/Feed to Peresós",
        ".config/caja/scripts/Feed to Peresós",
        ".local/share/nemo/actions/peresos-feed.nemo_action",
        ".local/share/kio/servicemenus/peresos-feed.desktop",
    ).map(userHome::resolve).filter { it.exists() && "feed --notify" in it.readText() }

private fun Path.named(name: String) = fileName?.toString().equals(name, ignoreCase = true)

/** The `peresos` command in `~/.local/bin`, when it leads into [app]. */
private fun linkTo(
    app: Path,
    userHome: Path,
): List<Path> {
    val link = userHome.resolve(".local/bin/peresos")
    if (!link.isSymbolicLink()) return emptyList()
    return listOfNotNull(link.takeIf { link.parent.resolve(it.readSymbolicLink()).normalize().startsWith(app) })
}

/**
 * Removes what is [installed], and [home] too when it is given. In a [terminal], apt asks for the
 * password with sudo; in the window, with pkexec. On Windows it all goes once Peresós quits.
 *
 * @throws IOException with what the person can do instead, when it cannot.
 */
@OptIn(ExperimentalPathApi::class)
internal fun uninstall(
    installed: Installed,
    home: Path?,
    terminal: Boolean,
) {
    if (installed is Installed.Windows) {
        val script = windowsScript(installed.folder, home, ProcessHandle.current().pid())
        val command = listOf("powershell", "-NoProfile", "-NonInteractive") +
            // Hidden only from the window: from a terminal it would hide that terminal, which it shares.
            (if (terminal) emptyList() else listOf("-WindowStyle", "Hidden")) +
            listOf("-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return
    }
    // A Linux or a Mac deletes the files of a running program; it keeps them until the program quits.
    when (installed) {
        is Installed.Apt -> {
            installed.menus.forEach { it.deleteIfExists() }
            val root = System.getProperty("user.name") == "root"
            val asRoot =
                when {
                    root -> emptyList()
                    terminal -> listOf("sudo")
                    onPath("pkexec") != null -> listOf("pkexec")
                    else -> throw IOException("Remove it in a terminal: sudo apt remove peresos")
                }
            val apt = ProcessBuilder(asRoot + listOf("apt-get", "remove", "-y", "peresos"))
            if (terminal) apt.inheritIO() else apt.redirectErrorStream(true)
            val process = apt.start()
            if (!terminal) process.inputStream.readAllBytes() // its output, read so that it never waits on a full pipe
            if (process.waitFor() != 0) throw IOException("apt did not remove it. Try it in a terminal: sudo apt remove peresos")
        }
        is Installed.Files -> installed.paths.forEach { if (it.isSymbolicLink()) it.deleteIfExists() else it.deleteRecursively() }
        else -> Unit
    }
    // Only once the app is gone: if apt fails, the pile is still there for the app that stays.
    home?.deleteRecursively()
}

/**
 * The PowerShell that removes Peresós from Windows once the process [pid] quits: its shortcut in
 * the Start menu, its line in the PATH of the person, [home] when it is given, and [folder] last.
 */
internal fun windowsScript(
    folder: Path,
    home: Path?,
    pid: Long,
): String {
    fun quoted(path: Path) = "'" + path.toString().replace("'", "''") + "'"
    return buildString {
        appendLine("\$ErrorActionPreference = 'SilentlyContinue'")
        appendLine("Wait-Process -Id $pid -Timeout 60")
        appendLine("\$dir = ${quoted(folder)}")
        appendLine("Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) \"Peres\$([char]0xF3)s.lnk\") -Force")
        // Feed to Peresós, in the menu of files in the Explorer.
        appendLine("foreach (\$type in 'image', '.pdf', 'text') { Remove-Item -LiteralPath \"HKCU:\\Software\\Classes\\SystemFileAssociations\\\$type\\shell\\Peresos\" -Recurse -Force }")
        appendLine("\$path = [Environment]::GetEnvironmentVariable('Path', 'User')")
        appendLine("if (\$path) { [Environment]::SetEnvironmentVariable('Path', ((\$path -split ';') | Where-Object { \$_ -and \$_.TrimEnd('\\') -ne \$dir }) -join ';', 'User') }")
        if (home != null) appendLine("Remove-Item -LiteralPath ${quoted(home)} -Recurse -Force")
        // The folder last, so that once it is gone everything is. The files of a program that just
        // quit can stay locked for a moment.
        appendLine("for (\$i = 0; \$i -lt 10 -and (Test-Path -LiteralPath \$dir); \$i++) { Remove-Item -LiteralPath \$dir -Recurse -Force; if (Test-Path -LiteralPath \$dir) { Start-Sleep 1 } }")
    }
}
