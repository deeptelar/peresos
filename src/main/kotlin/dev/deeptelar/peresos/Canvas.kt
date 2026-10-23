package dev.deeptelar.peresos

/** An empty pixel: the terminal's own background shows. */
internal const val EMPTY = -1

/** The color of the letters drawn on a canvas, like the z's of its sleep. */
private const val TEXT = 0xEFE6D2

/** An oval at height [y], [rx] pixels to each side of its middle column and [ry] up and down. */
internal class Oval(
    val y: Double,
    val rx: Double,
    val ry: Double,
) {
    /** Whether the pixel at [px], [py] falls inside, with the oval around column [x] and [grow] pixels bigger. */
    fun contains(
        x: Double,
        px: Int,
        py: Int,
        grow: Double = 0.0,
    ): Boolean {
        val dx = (px + 0.5 - x) / (rx + grow)
        val dy = (py + 0.5 - y) / (ry + grow)
        return dx * dx + dy * dy <= 1.0
    }
}

/**
 * Pixels in 24-bit colors, printed two to a character. Drawings place them in [width] columns and
 * [height] rows; [top] more rows above row 0 (an even number) and [right] more columns after the
 * last one give room for things the sloth wears.
 */
internal class Canvas(
    val width: Int,
    val height: Int,
    private val top: Int = 0,
    right: Int = 0,
) {
    init {
        require(top % 2 == 0 && height % 2 == 0) { "Two rows go in each line of text." }
    }

    private val columns = width + right
    private val pixels = Array(height + top) { IntArray(columns) { EMPTY } }

    /** Letters drawn in a whole character, by column and line. Line 0 holds rows 0 and 1. */
    val texts = mutableMapOf<Pair<Int, Int>, Char>()

    operator fun get(
        x: Int,
        y: Int,
    ): Int = pixels.getOrNull(y + top)?.getOrNull(x) ?: EMPTY

    operator fun set(
        x: Int,
        y: Int,
        rgb: Int,
    ) {
        if (y + top in pixels.indices && x in 0 until columns) pixels[y + top][x] = rgb
    }

    /** A pixel and its mirror on the other side of column [center], which falls between [center] - 1 and [center]. */
    fun both(
        x: Int,
        y: Int,
        rgb: Int,
        center: Int = width / 2,
    ) {
        this[x, y] = rgb
        this[2 * center - 1 - x, y] = rgb
    }

    /** A whole row, margins included. */
    fun row(
        y: Int,
        rgb: Int,
    ) = repeat(columns) { this[it, y] = rgb }

    /** Fills [oval] around column [x], only over pixels of the color [over] when it is given. */
    fun oval(
        x: Double,
        oval: Oval,
        rgb: Int,
        over: Int? = null,
    ) {
        for (py in -top until height) {
            for (px in 0 until columns) {
                if (oval.contains(x, px, py) && (over == null || this[px, py] == over)) this[px, py] = rgb
            }
        }
    }

    /** The pixels for a window to paint, with the top of the branch at row [branch] of the drawing. */
    fun picture(branch: Int): Picture =
        Picture(
            pixels = pixels.map { it.copyOf() },
            letters = texts.mapKeys { (at, _) -> at.first to at.second * 2 + top },
            branch = branch + top,
        )

    /** The lines to print: two pixels in each character, with the colors of a 24-bit terminal. */
    fun render(): List<String> =
        (-top until height step 2).map { y ->
            (0 until columns).joinToString("") { x ->
                val letter = texts[x to Math.floorDiv(y, 2)]?.takeIf { this[x, y] == EMPTY && this[x, y + 1] == EMPTY }
                if (letter != null) "$RESET${fg(TEXT)}$letter" else cell(this[x, y], this[x, y + 1])
            } + RESET
        }

    private fun cell(
        top: Int,
        bottom: Int,
    ): String =
        when {
            top == EMPTY && bottom == EMPTY -> "$RESET "
            top == EMPTY -> "$RESET${fg(bottom)}▄"
            bottom == EMPTY -> "$RESET${fg(top)}▀"
            else -> "${fg(top)}${bg(bottom)}▀"
        }

    private fun fg(rgb: Int) = "\u001B[38;2;${rgb shr 16 and 255};${rgb shr 8 and 255};${rgb and 255}m"

    private fun bg(rgb: Int) = "\u001B[48;2;${rgb shr 16 and 255};${rgb shr 8 and 255};${rgb and 255}m"

    private companion object {
        const val RESET = "\u001B[0m"
    }
}

/**
 * A drawing as plain pixels, for a window: rows of 24-bit colors, [EMPTY] where nothing is.
 *
 * @property letters letters two pixels tall, like the z's of its sleep, by the column and the row of their top.
 * @property branch the row of the top of the branch.
 */
internal class Picture(
    val pixels: List<IntArray>,
    val letters: Map<Pair<Int, Int>, Char>,
    val branch: Int,
) {
    val width: Int get() = pixels.first().size
    val height: Int get() = pixels.size
}
