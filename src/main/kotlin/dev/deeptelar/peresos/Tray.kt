package dev.deeptelar.peresos

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.deeptelar.peresos.Home.Companion.model
import dev.deeptelar.telar.GraphSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** About the size of a phone: the window sits in a corner of the screen, under its icon in the tray. */
internal val WINDOW_SIZE: DpSize = DpSize(400.dp, 800.dp)

/**
 * Opens its home, `peresos window`, like JetBrains Toolbox: an icon in the tray, and a small window
 * under it. Hiding the window, or clicking somewhere else, puts it back in the tray, where the icon
 * stays and shows what the sloth is doing; the desktop says when a bite is ready. Quit from the
 * window or the icon's menu. A second `peresos window` shows the window of the first.
 *
 * Without a tray, the window has a frame, and closing it quits. It shares [home] with the terminal.
 *
 * @param season and [weather] show this season or weather instead of the real one, to try them.
 */
fun window(
    home: Home,
    hungry: Boolean = false,
    season: Season? = null,
    weather: Weather? = null,
) {
    // Held until the app quits: a lock nobody holds on to is closed by the garbage collector.
    val running =
        home.window() ?: run {
            home.callWindow()
            return println("  Peresós is already in your tray. Its window opens there.")
        }
    running.use { app(home, hungry, season, weather) }
    exitProcess(0) // the threads of AWT and of the tray would keep it running
}

private fun app(
    home: Home,
    hungry: Boolean,
    season: Season?,
    weather: Weather?,
) {
    val tray = SystemTray.isSupported()
    application(exitProcessOnExit = false) {
        val client = remember { HttpClient(CIO) }
        val scope = rememberCoroutineScope()
        val model =
            remember {
                val graph = sloth(model(client), home.pile, hungry, diarist = model(client, think = false), about = home::about)
                SlothModel(home, graph, scope, hungry, meteo = OpenMeteo(client), season = season, weather = weather)
            }
        var shown by remember { mutableStateOf(true) }
        // When the window hid because the person pressed the tray icon, that click must not show it again.
        val hidden = remember { AtomicLong() }
        val icon = remember { if (tray) TrayIcon(trayImage(null), "Peresós").apply { isImageAutoSize = !LINUX } else null }
        val quit = {
            icon?.let { SystemTray.getSystemTray().remove(it) }
            client.close()
            exitApplication()
        }

        // A second `peresos window` asks this one to show itself.
        LaunchedEffect(Unit) {
            var seen = home.windowCalls()
            while (true) {
                delay(300)
                val now = home.windowCalls()
                if (now != seen) shown = true
                seen = now
            }
        }
        // Gemma takes a minute: when it is done and the window is hidden, the desktop says so.
        LaunchedEffect(Unit) {
            var before = model.session.snapshot.value
            model.session.snapshot.collect { now ->
                if (before is GraphSnapshot.Running && !shown) news(now, model)?.let { (title, text) -> notify(icon, title, text) }
                before = now
            }
        }

        if (icon != null) {
            val open = remember { MenuItem("Open Peresós") }
            DisposableEffect(icon) {
                icon.popupMenu =
                    PopupMenu().apply {
                        add(open.apply { addActionListener { shown = !shown } })
                        addSeparator()
                        add(MenuItem("Quit").apply { addActionListener { quit() } })
                    }
                icon.addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.button != MouseEvent.BUTTON1) return
                            shown = if (System.currentTimeMillis() - hidden.get() < 500) false else !shown
                        }
                    },
                )
                SystemTray.getSystemTray().add(icon)
                onDispose { SystemTray.getSystemTray().remove(icon) }
            }
            val snapshot by model.session.snapshot.collectAsState()
            val (badge, tooltip) = mood(snapshot, model)
            // The icon shows what the sloth is doing, drawn for the size the tray gives it.
            LaunchedEffect(badge, tooltip, shown) {
                icon.image = trayImage(badge)
                icon.toolTip = tooltip
                open.label = if (shown) "Hide Peresós" else "Open Peresós"
            }
        }

        Window(
            visible = shown,
            create = {
                ComposeWindow().apply {
                    title = "Peresós"
                    if (tray) {
                        // No button in the dock or the taskbar, and no frame: the icon in the tray is its button.
                        type = java.awt.Window.Type.UTILITY
                        isUndecorated = true
                        addWindowFocusListener(
                            object : WindowAdapter() {
                                override fun windowLostFocus(e: WindowEvent) {
                                    // A click inside can take the focus away for a moment: hide only if it stays away.
                                    Timer(250) {
                                        if (!isFocused) {
                                            hidden.set(System.currentTimeMillis())
                                            shown = false
                                        }
                                    }.apply { isRepeats = false }.start()
                                }
                            },
                        )
                    }
                    isResizable = false
                    size = Dimension(WINDOW_SIZE.width.value.toInt(), WINDOW_SIZE.height.value.toInt())
                    location = corner(size)
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    addWindowListener(
                        object : WindowAdapter() {
                            override fun windowClosing(e: WindowEvent) {
                                if (tray) shown = false else quit()
                            }
                        },
                    )
                }
            },
            dispose = ComposeWindow::dispose,
        ) {
            LaunchedEffect(shown) {
                if (shown) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            SlothScreen(model) {
                if (tray) WindowDraggableArea { TitleBar(onHide = { shown = false }, onQuit = quit) }
            }
        }
    }
}

/** The color of the icon's badge, if it needs one, and its tooltip. */
private fun mood(
    snapshot: GraphSnapshot<Sloth>,
    model: SlothModel,
): Pair<Int?, String> {
    val bite = snapshot.state?.bite
    return when {
        snapshot is GraphSnapshot.Running -> Ink.green.toArgb() to "Peresós is chewing. Slowly."
        snapshot is GraphSnapshot.Interrupted && SWALLOW in snapshot.nextNodes && bite != null -> Ink.yellow.toArgb() to "Peresós chewed “${bite.title}”. It waits for your answer."
        snapshot is GraphSnapshot.Stopped && snapshot.cause != null -> Ink.red.toArgb() to (model.trouble ?: "Peresós stopped halfway.")
        else -> null to "Peresós is asleep. ${model.pile.size} on the pile."
    }
}

/** What to tell the person when a run ends while the window is hidden. */
private fun news(
    snapshot: GraphSnapshot<Sloth>,
    model: SlothModel,
): Pair<String, String>? {
    val bite = snapshot.state?.bite
    return when {
        snapshot is GraphSnapshot.Interrupted && bite != null -> "Peresós chewed something" to "“${bite.title}”: take it, bin it, or later. Open it from the tray."
        snapshot is GraphSnapshot.Stopped && snapshot.cause != null -> "Peresós stopped halfway" to (model.trouble ?: "Try again from the tray.")
        else -> null
    }
}

private val LINUX = System.getProperty("os.name") == "Linux"

/** On Linux, the desktop's own notifications look right, and Java's do not; elsewhere the tray's. */
private fun notify(
    icon: TrayIcon?,
    title: String,
    text: String,
) {
    val sent =
        LINUX &&
            runCatching { ProcessBuilder("notify-send", "--app-name=Peresós", title, text).start() }.isSuccess
    if (!sent) icon?.displayMessage(title, text, TrayIcon.MessageType.INFO)
}

/**
 * The sloth's face, drawn for the tray: one row of letters per row of pixels. B is its fur, b the
 * edge of it, F its pale face, E the dark patches around its eyes, e its eyes and N its nose.
 */
private val FACE =
    listOf(
        "................",
        ".....bbbbbb.....",
        "...bbBBBBBBbb...",
        "..bBBBBBBBBBBb..",
        ".bBBFFFFFFFFBBb.",
        ".bBFFFFFFFFFFBb.",
        "bBFEEEFFFFEEEFBb",
        "bBFEeEFFFFEeEFBb",
        "bBFEEFFFFFFEEFBb",
        "bBFFFFFNNFFFFFBb",
        ".bBFFFFFFFFFFBb.",
        ".bBFFFFNNFFFFBb.",
        "..bBBFFFFFFBBb..",
        "...bbBBBBBBbb...",
        ".....bbbbbb.....",
        "................",
    )

private val FACE_COLORS = mapOf('B' to 0x9C7A5B, 'b' to 0x6E5440, 'F' to 0xE9DCC0, 'E' to 0x4A2F1C, 'e' to 0x15131F, 'N' to 0x2A1E16)

/**
 * The face on a tile of dark bark, with a square [badge] of that color in its corner. The tile fills
 * the icon because the trays of Linux draw Java's icons without transparency, on light grey.
 */
internal fun trayImage(badge: Int?): BufferedImage {
    // On Linux, Java paints a 24 pixel icon into the 16 pixels the tray gives it, so only its top left
    // corner shows: the face goes there. Elsewhere the tray fits the 16 pixels to its own size.
    val side = if (LINUX) SystemTray.getSystemTray().trayIconSize.height.coerceAtLeast(FACE.size) else FACE.size
    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until side) for (x in 0 until side) image.setRGB(x, y, Ink.woodDark.toArgb())
    FACE.forEachIndexed { y, row -> row.forEachIndexed { x, letter -> FACE_COLORS[letter]?.let { image.setRGB(x, y, it or 0xFF000000.toInt()) } } }
    if (badge != null) {
        for (y in 10 until 16) for (x in 10 until 16) image.setRGB(x, y, 0xFF2B1D14.toInt())
        for (y in 11 until 15) for (x in 11 until 15) image.setRGB(x, y, badge)
    }
    return image
}

/** The top right corner of the screen, under the tray, or the bottom right on Windows, where its tray is. */
private fun corner(size: Dimension): Point {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val x = screen.x + screen.width - size.width - 12
    val windows = System.getProperty("os.name").startsWith("Windows")
    return Point(x, if (windows) screen.y + screen.height - size.height - 12 else screen.y + 12)
}
