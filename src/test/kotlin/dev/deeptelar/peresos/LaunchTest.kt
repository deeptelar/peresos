package dev.deeptelar.peresos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LaunchTest {
    private val userHome = Files.createTempDirectory("peresos-launch")

    // Never the desktop of whoever runs the tests.
    private val desktop = userHome.resolve("Escritorio").createDirectories()

    /** What install.sh puts in the home on Linux, and its launcher. */
    private fun linuxApp(): Path {
        val launcher = userHome.resolve(".local/share/peresos/bin/peresos").createParentDirectories()
        launcher.writeText("#!/bin/sh")
        return launcher
    }

    @Test
    fun `on Linux it puts an entry on the desktop and in autostart, which open the window`() {
        val launcher = linuxApp()
        val launchers = Launchers(launcher, "Linux", userHome, desktop)

        launchers.turn(Opener.DESKTOP, on = true)
        launchers.turn(Opener.LOGIN, on = true)

        val icon = desktop.resolve("peresos.desktop")
        assertContains(icon.readText(), "Exec=\"$launcher\" window --here")
        assertContains(icon.readText(), "Icon=${userHome.resolve(".local/share/peresos/lib/peresos.png")}")
        assertTrue(Files.isExecutable(icon))
        assertContains(userHome.resolve(".config/autostart/peresos.desktop").readText(), "X-GNOME-Autostart-enabled=true")
        assertTrue(launchers.isOn(Opener.DESKTOP) && launchers.isOn(Opener.LOGIN))

        launchers.turn(Opener.DESKTOP, on = false)
        assertFalse(icon.exists())
        assertFalse(launchers.isOn(Opener.DESKTOP))
    }

    @Test
    fun `the package of apt uses the icon of the system`() {
        val launchers = Launchers(Path.of("/opt/peresos/bin/peresos"), "Linux", userHome, desktop)
        launchers.turn(Opener.LOGIN, on = true)
        assertContains(userHome.resolve(".config/autostart/peresos.desktop").readText(), "Icon=peresos\n")
    }

    @Test
    fun `on a Mac it links the app on the desktop, and starts it at login with a LaunchAgent`() {
        val app = userHome.resolve("Applications/Peresos.app")
        val launcher = app.resolve("Contents/MacOS/Peresos").createParentDirectories().apply { writeText("") }
        val launchers = Launchers(launcher, "Mac OS X", userHome, desktop)

        launchers.turn(Opener.DESKTOP, on = true)
        launchers.turn(Opener.LOGIN, on = true)

        assertEquals(app, desktop.resolve("Peresós").readSymbolicLink())
        val agent = userHome.resolve("Library/LaunchAgents/dev.deeptelar.peresos.plist").readText()
        assertContains(agent, "<string>$launcher</string>")
        assertContains(agent, "<key>RunAtLoad</key>")
    }

    @Test
    fun `on Windows it copies the shortcut of the zip, and says so when there is none`() {
        val folder = userHome.resolve("AppData/Local/Programs/Peresos").createDirectories()
        val launcher = folder.resolve("peresos.exe").apply { writeText("") }
        val launchers = Launchers(launcher, "Windows 11", userHome, desktop)

        val missing = assertFailsWith<IOException> { launchers.turn(Opener.DESKTOP, on = true) }
        assertContains(missing.message!!, "no shortcut")

        folder.resolve("peresos.lnk").writeText("a shortcut")
        launchers.turn(Opener.DESKTOP, on = true)
        assertEquals("a shortcut", desktop.resolve("Peresós.lnk").readText())
    }

    @Test
    fun `peresos uninstall takes away its icon and its start at login, and nothing of another app`() {
        val launcher = linuxApp()
        Launchers(launcher, "Linux", userHome, desktop).apply {
            turn(Opener.DESKTOP, on = true)
            turn(Opener.LOGIN, on = true)
        }
        // Another app's entry with the same name stays.
        val other = Files.createTempDirectory("other-home")
        val stranger = other.resolve(".config/autostart/peresos.desktop").createParentDirectories().apply { writeText("Exec=/usr/bin/something-else\n") }

        val installed = assertIs<Installed.Files>(installed(launcher.toString(), "Linux", userHome, desktop))
        assertTrue(installed.paths.containsAll(listOf(desktop.resolve("peresos.desktop"), userHome.resolve(".config/autostart/peresos.desktop"))))
        uninstall(installed, home = null, terminal = true)
        assertFalse(Files.exists(desktop.resolve("peresos.desktop"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(userHome.resolve(".config/autostart/peresos.desktop").exists())
        assertTrue(stranger.exists())
        assertFalse(installed(launcher.toString(), "Linux", other, desktop).let { it is Installed.Files && stranger in it.paths })
    }
}
