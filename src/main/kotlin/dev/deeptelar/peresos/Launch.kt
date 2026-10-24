package dev.deeptelar.peresos

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/** The places Peresós can open from without a terminal: an icon on the desktop, and the start at login, into the tray. */
internal enum class Opener {
    DESKTOP,
    LOGIN,
}

/**
 * Puts Peresós on the desktop, or starts it at login, and takes it away again: `peresos desktop`,
 * `peresos autostart`, and Settings in the window. Each opens the window, which lives in the tray.
 *
 * On Linux it is a desktop entry, on a Mac a link to the app and a LaunchAgent, and on Windows a copy
 * of `peresos.lnk`, the shortcut that comes in the zip: a PC whose policy keeps PowerShell from making
 * shortcuts can still copy one.
 *
 * @param launcher the `peresos` the jpackage launcher started, where it really is.
 * @param desktop the person's desktop folder, which Windows and Linux may move elsewhere.
 */
internal class Launchers(
    private val launcher: Path,
    private val os: String = System.getProperty("os.name"),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val desktop: Path = desktopFolder(os, userHome),
) {
    private val windows = os.startsWith("Windows")
    private val mac = os.startsWith("Mac")

    /** The file that puts it in [opener], whether it is there or not. */
    fun file(opener: Opener): Path =
        when {
            windows && opener == Opener.DESKTOP -> desktop.resolve("Peresós.lnk")
            windows -> Path.of(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "Peresós.lnk")
            mac && opener == Opener.DESKTOP -> desktop.resolve("Peresós")
            mac -> userHome.resolve("Library/LaunchAgents/dev.deeptelar.peresos.plist")
            opener == Opener.DESKTOP -> desktop.resolve("peresos.desktop")
            else -> userHome.resolve(".config/autostart/peresos.desktop")
        }

    fun isOn(opener: Opener): Boolean = file(opener).let { it.exists() || it.isSymbolicLink() }

    /** @throws IOException when it cannot, with what the person can do instead. */
    fun turn(
        opener: Opener,
        on: Boolean,
    ) {
        val file = file(opener)
        if (!on) {
            file.deleteIfExists()
            return
        }
        file.parent.createDirectories()
        when {
            windows -> {
                val shortcut = launcher.resolveSibling("peresos.lnk")
                if (!shortcut.exists()) throw IOException("This build of Peresós has no shortcut to copy. Install it from a release: ${Updater.WINDOWS_ONE_LINE}")
                shortcut.copyTo(file, overwrite = true)
            }
            mac && opener == Opener.DESKTOP -> {
                file.deleteIfExists()
                file.createSymbolicLinkPointingTo(app())
            }
            mac -> file.writeText(launchAgent())
            else -> {
                file.writeText(desktopEntry(autostart = opener == Opener.LOGIN))
                if (opener == Opener.DESKTOP) {
                    file.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
                    // GNOME opens an entry on the desktop only once it is trusted.
                    runCatching { ProcessBuilder("gio", "set", file.toString(), "metadata::trusted", "true").fresh().start().waitFor() }
                }
            }
        }
    }

    /** Peresos.app, around the launcher in Contents/MacOS. */
    private fun app(): Path = launcher.parent.parent.parent

    private fun desktopEntry(autostart: Boolean): String {
        // The .deb puts the icon among the system's; install.sh keeps it next to the app.
        val icon = if (launcher.startsWith("/opt/peresos")) "peresos" else launcher.parent.parent.resolve("lib/peresos.png").toString()
        return """
            [Desktop Entry]
            Type=Application
            Name=Peresós
            Comment=A sloth that eats one item of your backlog a day
            Exec="$launcher" window --here
            Icon=$icon
            Terminal=false
            StartupWMClass=dev-deeptelar-peresos-MainKt
            """.trimIndent() + if (autostart) "\nX-GNOME-Autostart-enabled=true\n" else "\n"
    }

    private fun launchAgent(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>dev.deeptelar.peresos</string>
            <key>ProgramArguments</key>
            <array>
                <string>$launcher</string>
                <string>window</string>
                <string>--here</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
        </dict>
        </plist>
        """.trimIndent() + "\n"

    companion object {
        /** The launchers of the Peresós that runs, or `null` when it runs from its source. */
        fun here(): Launchers? = System.getProperty("jpackage.app-path")?.let { Launchers(Path.of(it).let { path -> runCatching { path.toRealPath() }.getOrDefault(path) }) }
    }
}

/**
 * The desktop folder: on Windows where Explorer keeps it, which OneDrive may have moved; on Linux
 * where the XDG user folders say; else `Desktop` in the home.
 */
internal fun desktopFolder(
    os: String,
    userHome: Path,
): Path {
    val asked =
        runCatching {
            val command =
                when {
                    os.startsWith("Windows") -> listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "/v", "Desktop")
                    os.startsWith("Mac") -> null
                    else -> listOf("xdg-user-dir", "DESKTOP")
                } ?: return@runCatching null
            val output = ProcessBuilder(command).fresh().redirectErrorStream(true).start().let { process -> process.inputStream.readAllBytes().decodeToString().also { process.waitFor() } }
            // reg prints "    Desktop    REG_SZ    C:\Users\ana\OneDrive\Desktop"; xdg-user-dir the folder alone.
            val folder = if (os.startsWith("Windows")) output.lines().firstOrNull { "REG_" in it }?.substringAfter("REG_SZ")?.trim() else output.trim()
            folder?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }?.takeIf { it != userHome && it.exists() }
        }.getOrNull()
    return asked ?: userHome.resolve("Desktop")
}
