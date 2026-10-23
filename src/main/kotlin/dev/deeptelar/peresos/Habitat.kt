package dev.deeptelar.peresos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The scene is at least this many pixels wide and tall, so the biggest sloth fits, hanging and on top. */
private const val COLUMNS = 48
private const val ROWS = 48

/** The tick of the animations: one frame of the sloth every 200 ms, as in the terminal. */
@Composable
internal fun rememberTick(): Int {
    val tick by produceState(0) {
        while (true) {
            delay(200)
            value++
        }
    }
    return tick
}

/**
 * Where Peresós lives: a branch across a jungle, drawn in the same big pixels as the sloth. The
 * leaves take the colors of the [season], the sky shows the [weather], day or night. It plays the
 * sloth's animation twice, then rests a few seconds, and again. The sloth can be touched: the
 * pointer turns into a hand over it.
 *
 * @param frames the sloth's animation, from [Portrait.pictures].
 * @param onTop it lies on top of its branch, so the branch is lower, with room above it.
 * @param tick the frame of the animations, from [rememberTick].
 * @param touched the sloth reacting to a touch, from [Portrait.touched], in place of its animation.
 * @param shake how many pixels to move it sideways, to wiggle.
 * @param onTouch told where the person clicked the sloth.
 */
@Composable
internal fun Habitat(
    frames: List<Picture>,
    onTop: Boolean,
    night: Boolean,
    season: Season,
    weather: Weather,
    tick: Int,
    modifier: Modifier = Modifier,
    touched: Picture? = null,
    shake: Int = 0,
    onTouch: (Touch) -> Unit = {},
) {
    val letters = rememberTextMeasurer()
    val still = frames.first()
    val touch by rememberUpdatedState(onTouch)
    var over by remember { mutableStateOf(false) }
    val hands =
        Modifier
            .pointerInput(still, onTop) {
                detectTapGestures { at -> stage(size.width.toFloat(), size.height.toFloat(), still, onTop).part(still, at.x, at.y)?.let(touch) }
            }.pointerInput(still, onTop) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val at = event.changes.first().position
                        over = event.type != PointerEventType.Exit && stage(size.width.toFloat(), size.height.toFloat(), still, onTop).part(still, at.x, at.y) != null
                    }
                }
            }.pointerHoverIcon(if (over) PointerIcon.Hand else PointerIcon.Default)
    Canvas(modifier.then(hands)) {
        val stage = stage(size.width, size.height, still, onTop)
        val scale = stage.scale
        val columns = stage.columns
        val rows = stage.rows
        val branch = stage.branch
        val pixels = Pixels(this, scale)
        val sky = sky(weather, night)
        val leaves = leaves(season, weather, night)
        val far = lerp(sky.bottom, 0x1C2A24, if (night) 0.25f else 0.3f)

        for (y in 0 until rows) pixels.row(y, columns, lerp(sky.top, sky.bottom, y / rows.toFloat()))
        when {
            weather == Weather.CLEAR && night -> {
                stars(pixels, columns, rows - 6, tick)
                moon(pixels, (columns * 0.3f).toInt(), 11)
            }
            weather == Weather.CLEAR -> sun(pixels, (columns * 0.3f).toInt(), 11, tick)
            weather == Weather.CLOUDY && night -> moon(pixels, (columns * 0.3f).toInt(), 11)
            else -> Unit
        }
        if (weather != Weather.CLEAR && weather != Weather.FOG) clouds(pixels, columns, rows, weather, night, tick)
        for ((at, wide) in listOf(0.08f to 3, 0.8f to 4, 0.93f to 2)) {
            for (x in 0 until wide) pixels.column((columns * at).toInt() + x, 0, rows, far)
        }
        undergrowth(pixels, columns, rows, lerp(far, 0x1C2A24, 0.2f), snowy = weather == Weather.SNOW)
        canopy(pixels, columns, leaves, sparse = season == Season.WINTER, snowy = weather == Weather.SNOW)
        vines(pixels, columns, branch, tick, leaves)
        pixels.row(branch, columns, Portrait.BRANCH)
        pixels.row(branch + 1, columns, Portrait.BRANCH_SHADOW)
        tufts(pixels, columns, branch, leaves)
        if (weather == Weather.SNOW) pixels.row(branch - 1, columns, SNOW)
        if (weather == Weather.FOG) fog(pixels, columns, rows, night, tick)

        // The sloth, its branch on the branch of the scene.
        val cycle = frames.size * 2 + 15
        val frame = touched ?: frames[(tick % cycle).let { if (it < frames.size * 2) it % frames.size else 0 }]
        val left = stage.left + shake
        val top = stage.top
        frame.pixels.forEachIndexed { y, line -> line.forEachIndexed { x, rgb -> if (rgb != EMPTY) pixels[left + x, top + y] = rgb } }
        val dark = night || weather == Weather.STORM
        val style = TextStyle(fontFamily = PixelFont, fontSize = if (scale >= 10) BIG else BODY, color = color(if (dark) 0xEFE6D2 else 0x2E3A30))
        frame.letters.forEach { (at, letter) ->
            val (x, y) = at
            drawText(letters, letter.toString(), Offset((left + x) * scale, (top + y) * scale), style)
        }

        // What falls, in front of everything.
        when {
            weather == Weather.RAIN || weather == Weather.STORM -> rain(pixels, columns, rows, night, tick)
            weather == Weather.SNOW -> snow(pixels, columns, rows, tick)
            season == Season.AUTUMN -> falling(pixels, columns, rows, tick, listOf(leaves.accent, leaves.light))
            season == Season.SPRING -> falling(pixels, columns, rows, tick, listOf(leaves.accent))
        }
        // Lightning, now and then: a bright flash, then a fainter one.
        if (weather == Weather.STORM && tick % 37 >= 35) pixels.wash(columns, rows, 0xFFFFFF, if (tick % 37 == 35) 0.55f else 0.25f)
    }
}

/**
 * Where everything sits in a habitat of [width] by [height] screen pixels: the size of one of its
 * pixels, how many fit, the row of the branch, and the top left corner of the sloth's picture.
 */
internal class Stage(
    val scale: Float,
    val columns: Int,
    val rows: Int,
    val branch: Int,
    val left: Int,
    val top: Int,
) {
    /** The part of the sloth of [picture] at [x], [y] on the screen, or at a pixel next to it, so a near miss counts. */
    fun part(
        picture: Picture,
        x: Float,
        y: Float,
    ): Touch? {
        val px = floor(x / scale).toInt() - left
        val py = floor(y / scale).toInt() - top
        return listOf(0 to 0, 0 to -1, 0 to 1, -1 to 0, 1 to 0).firstNotNullOfOrNull { (dx, dy) ->
            picture.pixels.getOrNull(py + dy)?.getOrNull(px + dx)?.let(Portrait::part)
        }
    }
}

internal fun stage(
    width: Float,
    height: Float,
    picture: Picture,
    onTop: Boolean,
): Stage {
    val scale = max(2f, floor(min(width / COLUMNS, height / ROWS)))
    val columns = (width / scale).toInt() + 1
    val rows = (height / scale).toInt() + 1
    val branch = if (onTop) rows * 2 / 3 else rows / 3
    return Stage(scale, columns, rows, branch, columns / 2 - picture.width / 2, branch - picture.branch)
}

/** A painter of one picture, for the window's icon. */
internal class PicturePainter(
    private val picture: Picture,
) : Painter() {
    override val intrinsicSize: Size = Size(picture.width.toFloat(), picture.height.toFloat())

    override fun DrawScope.onDraw() {
        val scale = min(size.width / picture.width, size.height / picture.height)
        val pixels = Pixels(this, scale)
        picture.pixels.forEachIndexed { y, line -> line.forEachIndexed { x, rgb -> if (rgb != EMPTY) pixels[x, y] = rgb } }
    }
}

/** Draws big square pixels of [scale] screen pixels. */
private class Pixels(
    private val scope: DrawScope,
    private val scale: Float,
) {
    operator fun set(
        x: Int,
        y: Int,
        rgb: Int,
    ) = scope.drawRect(color(rgb), Offset(x * scale, y * scale), Size(scale + 0.5f, scale + 0.5f))

    fun row(
        y: Int,
        columns: Int,
        rgb: Int,
    ) = scope.drawRect(color(rgb), Offset(0f, y * scale), Size(columns * scale, scale + 0.5f))

    fun column(
        x: Int,
        from: Int,
        to: Int,
        rgb: Int,
    ) = scope.drawRect(color(rgb), Offset(x * scale, from * scale), Size(scale + 0.5f, (to - from) * scale))

    /** A see-through pixel, for fog. */
    fun mist(
        x: Int,
        y: Int,
        rgb: Int,
        alpha: Float,
    ) = scope.drawRect(color(rgb).copy(alpha = alpha), Offset(x * scale, y * scale), Size(scale, scale))

    /** Covers everything with a see-through color, like a flash of lightning. */
    fun wash(
        columns: Int,
        rows: Int,
        rgb: Int,
        alpha: Float,
    ) = scope.drawRect(color(rgb).copy(alpha = alpha), Offset.Zero, Size(columns * scale, rows * scale))
}

private class Sky(
    val top: Int,
    val bottom: Int,
)

private fun sky(
    weather: Weather,
    night: Boolean,
): Sky =
    when (weather) {
        Weather.CLEAR -> if (night) Sky(0x0D1430, 0x22355A) else Sky(0x86CBEA, 0xCDEFD9)
        Weather.CLOUDY -> if (night) Sky(0x161C2C, 0x2C3446) else Sky(0xA3B6C4, 0xD5DFD9)
        Weather.FOG -> if (night) Sky(0x262C36, 0x3A424C) else Sky(0xC4CDCC, 0xE4E8E2)
        Weather.RAIN -> if (night) Sky(0x10151F, 0x222B37) else Sky(0x75889A, 0xAAB8BC)
        Weather.SNOW -> if (night) Sky(0x1C2436, 0x38445A) else Sky(0xC2CEDA, 0xEEF2F5)
        Weather.STORM -> if (night) Sky(0x090C14, 0x181E2C) else Sky(0x4C5766, 0x7E8A94)
    }

/** The colors of the leaves: the darker and the lighter green (or red, in autumn), and a touch of something else. */
private class Leaves(
    val dark: Int,
    val light: Int,
    val accent: Int,
    val stem: Int,
)

private fun leaves(
    season: Season,
    weather: Weather,
    night: Boolean,
): Leaves {
    val day =
        when (season) {
            Season.SPRING -> Leaves(dark = 0x4C8A36, light = 0x8CC765, accent = 0xF4A9C6, stem = 0x5A8A3A) // blossoms
            Season.SUMMER -> Leaves(dark = 0x2E6A28, light = 0x5E9E3A, accent = 0xF2D35B, stem = 0x4E7E32)
            Season.AUTUMN -> Leaves(dark = 0xA0461E, light = 0xE08A2E, accent = 0xF2C94C, stem = 0x8A6A3A)
            Season.WINTER -> Leaves(dark = 0x34503E, light = 0x58745E, accent = 0x58745E, stem = 0x5E6B5A)
        }
    // Grey weather and the night dim them.
    val dim = (if (night) 0.55f else 0f) + (if (weather == Weather.RAIN || weather == Weather.STORM || weather == Weather.FOG) 0.15f else 0f)
    fun shade(rgb: Int) = lerp(rgb, 0x0A0F1A, dim)
    return Leaves(shade(day.dark), shade(day.light), shade(day.accent), shade(day.stem))
}

/** Leaves along the top. In winter they are sparse, and snow lies on them when it snows. */
private fun canopy(
    pixels: Pixels,
    columns: Int,
    leaves: Leaves,
    sparse: Boolean,
    snowy: Boolean,
) {
    for (x in 0 until columns) {
        val deep = 4 + (sin(x * 0.55) * 1.5 + sin(x * 0.21) * 2).toInt()
        for (y in 0..deep) {
            if (sparse && (x * 5 + y * 7) % 4 == 0 && y > 1) continue
            pixels[x, y] =
                when {
                    snowy && y == deep -> SNOW
                    y == deep || (x * 7 + y * 3) % 5 == 0 -> leaves.light
                    (x * 11 + y * 13) % 17 == 0 -> leaves.accent
                    else -> leaves.dark
                }
        }
    }
}

/** Vines hanging from the leaves, swaying a little, with a leaf every few pixels. */
private fun vines(
    pixels: Pixels,
    columns: Int,
    branch: Int,
    tick: Int,
    leaves: Leaves,
) {
    for ((at, length) in listOf(0.17f to branch + 4, 0.68f to branch - 3, 0.87f to branch + 14)) {
        val x = (columns * at).toInt()
        for (y in 3 until length) {
            val sway = ((y + tick / 4) / 6) % 2
            pixels[x + sway, y] = leaves.stem
            if (y % 5 == 0) pixels[x + sway + if (y % 10 == 0) 1 else -1, y] = leaves.light
        }
    }
}

/** Tufts of leaves growing on the branch, near its ends. */
private fun tufts(
    pixels: Pixels,
    columns: Int,
    branch: Int,
    leaves: Leaves,
) {
    for (x0 in listOf(3, columns - 9)) {
        for ((dx, height) in listOf(0 to 2, 1 to 3, 2 to 4, 3 to 2, 4 to 3, 5 to 1)) {
            for (y in 1..height) pixels[x0 + dx, branch - y] = if (y == height) leaves.light else leaves.dark
        }
    }
}

/** Bushes along the bottom, in the color of the far trees, with snow on top when it snows. */
private fun undergrowth(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    rgb: Int,
    snowy: Boolean,
) {
    for (x in 0 until columns) {
        val high = 3 + (sin(x * 0.45) * 1.5 + sin(x * 0.17 + 1) * 1.5).toInt()
        pixels.column(x, rows - high, rows, rgb)
        if (snowy) pixels[x, rows - high] = SNOW
    }
}

private fun moon(
    pixels: Pixels,
    cx: Int,
    cy: Int,
) {
    for (y in -3..3) for (x in -3..3) if (x * x + y * y <= 10) pixels[cx + x, cy + y] = if (x * x + y * y <= 3 && x < 0) 0xF4EFD8 else 0xE2DCC0
}

/** The sun, with rays that grow and shrink. */
private fun sun(
    pixels: Pixels,
    cx: Int,
    cy: Int,
    tick: Int,
) {
    for (y in -3..3) for (x in -3..3) if (x * x + y * y <= 10) pixels[cx + x, cy + y] = if (x * x + y * y <= 3 && x < 0) 0xFFF4B8 else 0xFFD95A
    val reach = 5 + (tick / 5) % 2
    for ((dx, dy) in listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)) {
        pixels[cx + dx * reach, cy + dy * reach] = 0xFFD95A
        pixels[cx + dx * (reach - 1), cy + dy * (reach - 1)] = 0xFFD95A
    }
    for ((dx, dy) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) pixels[cx + dx * (reach - 1), cy + dy * (reach - 1)] = 0xFFE58A
}

/** Stars between the leaves and the undergrowth, always in the same places, and a few twinkle. */
private fun stars(
    pixels: Pixels,
    columns: Int,
    bottom: Int,
    tick: Int,
) {
    val places = Random(7)
    repeat(columns * bottom / 90) { i ->
        val x = places.nextInt(columns)
        val y = 7 + places.nextInt(max(1, bottom - 7))
        val twinkle = (tick / 3 + i) % 11 == 0
        pixels[x, y] = if (twinkle) 0xFFFFFF else 0x7C86A8
    }
}

/** Clouds drifting slowly to the right, white or grey with the weather. */
private fun clouds(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    weather: Weather,
    night: Boolean,
    tick: Int,
) {
    val (light, dark) =
        when {
            night -> 0x3A4458 to 0x2A3244
            weather == Weather.CLOUDY || weather == Weather.SNOW -> 0xF4F6F8 to 0xD6DDE3
            else -> 0x9AA6B0 to 0x7A8792
        }
    val places = Random(3)
    repeat(if (weather == Weather.CLOUDY) 4 else 6) { i ->
        val wide = 8 + places.nextInt(8)
        val y = 7 + places.nextInt(max(1, rows / 3))
        val x = (places.nextInt(columns + wide) + tick / (6 + i)) % (columns + wide) - wide
        for (dx in 0 until wide) {
            pixels[x + dx, y + 1] = dark
            if (dx in 1 until wide - 1) pixels[x + dx, y] = light
            if (dx in wide / 4 until wide * 3 / 4) pixels[x + dx, y - 1] = light
        }
    }
}

/** Rain: short streaks falling fast, and slanting a little. */
private fun rain(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    night: Boolean,
    tick: Int,
) {
    val drop = if (night) 0x5E7698 else 0xD4E2EE
    val places = Random(5)
    repeat(columns * rows / 45) {
        val x0 = places.nextInt(columns)
        val y = (places.nextInt(rows) + tick * 3) % rows
        val x = (x0 + y / 4) % columns
        pixels[x, y] = drop
        pixels[x, y - 1] = drop
    }
}

/** Snow: flakes falling slowly, swaying as they go. */
private fun snow(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    tick: Int,
) {
    val places = Random(9)
    repeat(columns * rows / 50) { i ->
        val x0 = places.nextInt(columns)
        val y = (places.nextInt(rows) + tick / 2) % rows
        pixels[x0 + (sin((y + i) * 0.4) * 1.2).toInt(), y] = SNOW
    }
}

/** A few leaves or petals floating down, zigzagging. */
private fun falling(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    tick: Int,
    colors: List<Int>,
) {
    val places = Random(11)
    repeat(6) { i ->
        val x0 = places.nextInt(columns)
        val y = (places.nextInt(rows) + tick / 2) % rows
        pixels[x0 + (y / 3) % 2, y] = colors[i % colors.size]
    }
}

/** Banks of fog drifting across the trees. */
private fun fog(
    pixels: Pixels,
    columns: Int,
    rows: Int,
    night: Boolean,
    tick: Int,
) {
    val mist = if (night) 0x8A93A0 else 0xF2F4F2
    for (band in 0 until 3) {
        val y0 = rows * (3 + band * 2) / 10
        for (x in 0 until columns) {
            // Each column of a bank has its own height, so its edges are ragged, and the bank drifts.
            val at = x + tick / (3 + band) + band * 7
            val thick = 2 + ((sin(at * 0.5) + sin(at * 0.23)) * 1.5).toInt()
            for (y in y0 - thick / 2..y0 + thick) pixels.mist(x, y, mist, if (y == y0 - thick / 2 || y == y0 + thick) 0.18f else 0.3f)
        }
    }
}

private const val SNOW = 0xF6F8FA

private fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)

private fun lerp(
    from: Int,
    to: Int,
    at: Float,
): Int {
    fun channel(shift: Int) = ((from shr shift and 255) + ((to shr shift and 255) - (from shr shift and 255)) * at).toInt()
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
