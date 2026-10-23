package dev.deeptelar.peresos

/** The sloth as pixel art, hanging from its branch, a little rounder for every item it finished. */
object Portrait {
    private const val WIDTH = 16

    private val head =
        listOf(
            "bbbbbbbbbbbbbbbb",
            "BBCBCBBBBBBCBCBB",
            "..FF........FF..",
            "..FF.dddddd.FF..",
            "..FFddFFFFddFF..",
            "..FFdMMMMMMdFF..",
            "...FMMMMMMMMF...",
            "...FEoMMMMoEF...",
            "...EEMMNNMMEE...",
            "...FMMmMMmMMF...",
            "....FFMmmMFF....",
        )

    private val colors =
        mapOf(
            'b' to 0xB07440, // branch
            'B' to 0x6E4528, // branch, shadow
            'C' to 0xE2B07A, // claws
            'F' to 0x9C7A5B, // fur
            'd' to 0x6F5440, // fur, dark
            'M' to 0xE9DCC0, // face
            'E' to 0x4A2F1C, // the dark stripes that run down from the eyes
            'o' to 0x15131F, // eyes
            'N' to 0x2A1E16, // nose
            'm' to 0x6E4528, // smile
            'L' to 0xC9A27E, // belly
        )

    /** The lines to print: two pixels in each character, with the colors of a 24-bit terminal. */
    fun lines(roundness: Int): List<String> {
        val pixels = head + body(roundness)
        return pixels.chunked(2).map { (top, bottom) -> (0 until WIDTH).joinToString("") { cell(colors[top[it]], colors[bottom[it]]) } + RESET }
    }

    private fun body(roundness: Int): List<String> {
        val grow = minOf(roundness / 2, 3)
        return listOf(8, 10, 10, 10, 8).mapIndexed { row, base ->
            val width = minOf(base + 2 * grow, WIDTH)
            val belly = if (row in 1..3) width - 4 else 0
            val fur = (width - belly) / 2
            val line = "F".repeat(fur) + "L".repeat(belly) + "F".repeat(width - belly - fur)
            val side = (WIDTH - width) / 2
            ".".repeat(side) + line + ".".repeat(WIDTH - width - side)
        }
    }

    private fun cell(top: Int?, bottom: Int?): String =
        when {
            top == null && bottom == null -> "$RESET "
            top == null -> "$RESET${fg(bottom!!)}▄"
            bottom == null -> "$RESET${fg(top)}▀"
            else -> "${fg(top)}${bg(bottom)}▀"
        }

    private fun fg(rgb: Int) = "\u001B[38;2;${rgb shr 16 and 255};${rgb shr 8 and 255};${rgb and 255}m"

    private fun bg(rgb: Int) = "\u001B[48;2;${rgb shr 16 and 255};${rgb shr 8 and 255};${rgb and 255}m"

    private const val RESET = "\u001B[0m"
}
