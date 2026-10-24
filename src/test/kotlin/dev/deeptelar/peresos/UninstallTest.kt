package dev.deeptelar.peresos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UninstallTest {
    private val userHome = Files.createTempDirectory("peresos-uninstall")

    /** What install.sh puts in the home on Linux: the app, the command, and the menu entry. */
    private fun linuxHome(): Path {
        val app = userHome.resolve(".local/share/peresos")
        app.resolve("bin/peresos").createParentDirectories().writeText("#!/bin/sh")
        userHome.resolve(".local/bin/peresos").createParentDirectories().createSymbolicLinkPointingTo(app.resolve("bin/peresos"))
        userHome.resolve(".local/share/applications/peresos.desktop").createParentDirectories().writeText("Exec=$app/bin/peresos window\n")
        return app
    }

    @Test
    fun `from its source nothing is installed`() {
        assertEquals(Installed.Source, installed(launcher = null, os = "Linux", userHome = userHome))
    }

    @Test
    fun `in opt it is the package of apt`() {
        assertEquals(Installed.Apt, installed("/opt/peresos/bin/peresos", "Linux", userHome))
    }

    @Test
    fun `in the home of a Linux it is the app, the command and the menu entry`() {
        val app = linuxHome()
        val installed = installed("$app/bin/peresos", "Linux", userHome)
        assertEquals(Installed.Files(listOf(app, userHome.resolve(".local/bin/peresos"), userHome.resolve(".local/share/applications/peresos.desktop"))), installed)
    }

    @Test
    fun `a command or a menu entry of something else stays`() {
        val app = linuxHome()
        val other = userHome.resolve("other/bin/peresos").createParentDirectories().apply { writeText("#!/bin/sh") }
        Files.delete(userHome.resolve(".local/bin/peresos"))
        userHome.resolve(".local/bin/peresos").createSymbolicLinkPointingTo(other)
        userHome.resolve(".local/share/applications/peresos.desktop").writeText("Exec=$other window\n")
        assertEquals(Installed.Files(listOf(app)), installed("$app/bin/peresos", "Linux", userHome))
    }

    @Test
    fun `a folder that no installer uses is not removed`() {
        assertNull(installed("$userHome/projects/bin/peresos", "Linux", userHome))
        assertNull(installed("/c/Users/ana/Downloads/peresos.exe", "Windows 11", userHome))
        assertNull(installed("$userHome/Applications/Other.app/Contents/MacOS/Peresos", "Mac OS X", userHome))
    }

    @Test
    fun `on a Mac it is the app and the command`() {
        val app = userHome.resolve("Applications/Peresos.app").createDirectories()
        userHome.resolve(".local/bin/peresos").createParentDirectories().createSymbolicLinkPointingTo(app.resolve("Contents/MacOS/Peresos"))
        assertEquals(Installed.Files(listOf(app, userHome.resolve(".local/bin/peresos"))), installed("$app/Contents/MacOS/Peresos", "Mac OS X", userHome))
    }

    @Test
    fun `on Windows it is the folder of install ps1`() {
        assertEquals(Installed.Windows(Path.of("/c/Users/ana/AppData/Local/Programs/Peresos")), installed("/c/Users/ana/AppData/Local/Programs/Peresos/peresos-window.exe", "Windows 11", userHome))
    }

    @Test
    fun `it removes what install sh put in the home, and keeps the pile unless told`() {
        val app = linuxHome()
        val pile = userHome.resolve(".peresos/pile.md").createParentDirectories().apply { writeText("- the taxes\n") }
        val installed = installed("$app/bin/peresos", "Linux", userHome)!!
        uninstall(installed, home = null, terminal = true)
        assertFalse(app.exists())
        assertFalse(Files.exists(userHome.resolve(".local/bin/peresos"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(userHome.resolve(".local/share/applications/peresos.desktop").exists())
        assertTrue(pile.exists())
    }

    @Test
    fun `told to forget, it deletes its home too`() {
        val app = linuxHome()
        val home = userHome.resolve(".peresos")
        home.resolve("done/taxes.md").createParentDirectories().writeText("Done.")
        uninstall(installed("$app/bin/peresos", "Linux", userHome)!!, home = home, terminal = true)
        assertFalse(home.exists())
    }

    @Test
    fun `the script of Windows waits for Peresós, then removes its folder, shortcut and PATH`() {
        val script = windowsScript(Path.of("/c/Users/o'brien/Programs/Peresos"), home = null, pid = 4242)
        assertContains(script, "Wait-Process -Id 4242")
        assertContains(script, "\$dir = '/c/Users/o''brien/Programs/Peresos'")
        assertContains(script, "Peres\$([char]0xF3)s.lnk")
        assertContains(script, "SetEnvironmentVariable('Path'")
        assertTrue(script.trim().lines().last().startsWith("for "), "the folder goes last, so a gone folder means it is all gone")
        assertFalse("o''brien/.peresos" in script)
        assertContains(windowsScript(Path.of("/c/Peresos"), home = Path.of("/c/Users/ana/.peresos"), pid = 1), "Remove-Item -LiteralPath '/c/Users/ana/.peresos' -Recurse -Force")
    }
}
