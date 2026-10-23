package dev.deeptelar.peresos

import kotlin.math.floor

/**
 * The sloth as pixel art, hanging from its branch. It is born a baby and grows bigger and rounder
 * with every item it finishes, and in each phase it does something different while you look.
 */
object Portrait {
    /** How grown up the sloth is, and what it does in its animation. */
    enum class Phase(val title: String) {
        BABY("a baby, kicking its little legs"),
        YOUNG("young, chewing a leaf"),
        GROWN("grown up, yawning"),
        ROUND("big and round, asleep"),
    }

    fun phase(roundness: Int): Phase =
        when {
            roundness < 2 -> Phase.BABY
            roundness < 5 -> Phase.YOUNG
            roundness < 10 -> Phase.GROWN
            else -> Phase.ROUND
        }

    /** The portrait standing still: the first frame of its animation. */
    fun lines(roundness: Int): List<String> = frames(roundness).first()

    /** The frames of the animation, to show one after another and then again. All have the same size. */
    fun frames(roundness: Int): List<List<String>> {
        val phase = phase(roundness)
        return poses.getValue(phase).map { pose -> draw(builds.getValue(phase), pose).render() }
    }

    /** An oval around the middle column, at height [y]. */
    private class Oval(val y: Double, val rx: Double, val ry: Double)

    /** The size of the picture, and of the head and the body in it, in pixels. */
    private class Build(val width: Int, val height: Int, val head: Oval, val body: Oval, val arm: Int, val eyes: Int)

    // A baby is mostly head. The body grows faster than the head, until it is the roundest thing on the branch.
    private val builds =
        mapOf(
            Phase.BABY to Build(14, 18, head = Oval(6.5, 4.5, 4.0), body = Oval(12.0, 3.0, 2.5), arm = 1, eyes = 2),
            Phase.YOUNG to Build(18, 22, head = Oval(7.0, 5.5, 4.5), body = Oval(14.5, 5.0, 4.5), arm = 2, eyes = 2),
            Phase.GROWN to Build(22, 26, head = Oval(7.5, 6.0, 5.0), body = Oval(17.5, 7.5, 6.5), arm = 2, eyes = 2),
            Phase.ROUND to Build(28, 30, head = Oval(8.0, 6.5, 5.5), body = Oval(19.5, 12.0, 9.0), arm = 2, eyes = 3),
        )

    /** What moves in one frame. */
    private data class Pose(
        val closed: Boolean = false,
        val kick: Int = 0,
        val leaf: Int = 0,
        val mouth: Int = 0,
        val breath: Int = 0,
        val z: Int = -1,
    )

    private val poses =
        mapOf(
            Phase.BABY to listOf(0, 1, 0, -1, 0, 1, 0, -1, 0).map { Pose(kick = it) } + listOf(Pose(closed = true), Pose()),
            Phase.YOUNG to listOf(3, 3, 2, 2, 1, 1, 0, 0).mapIndexed { i, leaf -> Pose(leaf = leaf, mouth = i % 2) } + listOf(Pose(), Pose(closed = true), Pose()),
            Phase.GROWN to listOf(0, 0, 1, 2, 2, 2, 1, 0, 0, 0).mapIndexed { i, mouth -> Pose(mouth = mouth, closed = i in 3..7) },
            Phase.ROUND to (0 until 8).map { Pose(closed = true, breath = if (it % 4 < 2) 0 else 1, z = it) },
        )

    private fun draw(
        build: Build,
        pose: Pose,
    ): Canvas {
        val canvas = Canvas(build.width, build.height)
        val cx = build.width / 2 // the face sits on the two middle columns, cx - 1 and cx
        val head = build.head
        val body = build.body
        val breath = pose.breath * 0.5

        canvas.row(0, 'b')
        canvas.row(1, 'B')
        // The arms reach up from the shoulders to the branch, where the claws hold on.
        val claw = floor(cx - head.rx).toInt() - build.arm
        canvas.arm(claw, 1, floor(cx - body.rx * 0.8).toInt(), (body.y - body.ry * 0.5).toInt(), build.arm)
        repeat(build.arm) { canvas.both(claw + it, 1, 'C') }
        // Two short legs hang below. A baby kicks them, one down while the other goes up.
        val foot = floor(body.y + body.ry).toInt() + 1
        val leg = floor(cx - body.rx * 0.5).toInt() - 1
        canvas.leg(leg, foot + pose.kick)
        canvas.leg(build.width - 2 - leg, foot - pose.kick)

        canvas.oval(cx.toDouble(), body.y, body.rx + breath, body.ry + breath, 'F')
        canvas.oval(cx.toDouble(), body.y + 0.5, body.rx * 0.75 + breath, body.ry * 0.75 + breath, 'L', over = 'F')
        canvas.closeGaps(head.y.toInt(), body.y.toInt())
        canvas.oval(cx.toDouble(), head.y, head.rx, head.ry, 'F')
        canvas.oval(cx.toDouble(), head.y - 0.5, head.rx - 1, head.ry - 1, 'd', over = 'F')
        canvas.oval(cx.toDouble(), head.y + 0.5, head.rx - 1, head.ry - 1.2, 'M', over = 'd')

        // The face: eyes in the dark stripes that run down from them, the nose, and the mouth.
        val eye = head.y.toInt()
        val eyeX = cx - 1 - build.eyes
        canvas.both(eyeX, eye, if (pose.closed) 'E' else 'o')
        canvas.both(eyeX - 1, eye, 'E')
        canvas.both(eyeX - 2, eye + 1, 'E')
        canvas.both(cx - 1, eye + 1, 'N')
        val mouth = eye + 2
        when (pose.mouth) {
            0 -> {
                canvas.both(cx - 2, mouth, 'm')
                canvas.both(cx - 1, mouth + 1, 'm')
            }
            1 -> {
                canvas.both(cx - 2, mouth, 'm')
                canvas.both(cx - 1, mouth, 'O')
            }
            else -> {
                canvas.both(cx - 1, mouth, 'O')
                canvas.both(cx - 1, mouth + 1, 'P')
            }
        }
        repeat(pose.leaf) { canvas[cx + 1 + it, mouth + 1] = 'G' }
        if (pose.leaf == 3) canvas[cx + 3, mouth] = 'g'

        // Asleep, the z's float up beside its head.
        if (pose.z >= 0) {
            val x = floor(cx + head.rx).toInt() + build.arm + 2
            val line = head.y.toInt() / 2
            val stages = listOf(Triple(x, line, 'z'), Triple(x + 1, line - 1, 'z'), Triple(x + 1, line - 2, 'Z'), Triple(x + 2, line - 3, 'Z'))
            for (stage in listOf(pose.z % 4, (pose.z + 2) % 4)) stages[stage].let { (sx, sy, letter) -> canvas.texts[sx to sy] = letter }
        }
        return canvas
    }

    private class Canvas(
        val width: Int,
        val height: Int,
    ) {
        private val pixels = Array(height) { CharArray(width) { '.' } }

        /** Letters drawn in a whole character, by column and line. */
        val texts = mutableMapOf<Pair<Int, Int>, Char>()

        operator fun set(
            x: Int,
            y: Int,
            pixel: Char,
        ) {
            if (x in 0 until width && y in 0 until height) pixels[y][x] = pixel
        }

        /** A pixel and its mirror on the other side of the middle. */
        fun both(
            x: Int,
            y: Int,
            pixel: Char,
        ) {
            this[x, y] = pixel
            this[width - 1 - x, y] = pixel
        }

        fun row(
            y: Int,
            pixel: Char,
        ) = repeat(width) { this[it, y] = pixel }

        fun oval(
            x: Double,
            y: Double,
            rx: Double,
            ry: Double,
            pixel: Char,
            over: Char? = null,
        ) {
            for (py in 0 until height) {
                for (px in 0 until width) {
                    val dx = (px + 0.5 - x) / rx
                    val dy = (py + 0.5 - y) / ry
                    if (dx * dx + dy * dy <= 1.0 && (over == null || pixels[py][px] == over)) pixels[py][px] = pixel
                }
            }
        }

        /** A left arm from the claw down to the shoulder, and the right one mirrored. */
        fun arm(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            thickness: Int,
        ) {
            val steps = maxOf(toY - fromY, 1) * 2
            for (step in 0..steps) {
                val x = fromX + (toX - fromX) * step / steps
                val y = fromY + (toY - fromY) * step / steps
                repeat(thickness) { both(x + it, y, 'F') }
            }
        }

        /** Fills the holes of one or two pixels between an arm and the body, in rows [fromY] to [toY]. */
        fun closeGaps(
            fromY: Int,
            toY: Int,
        ) {
            for (y in fromY..toY) {
                for (x in 1 until width - 1) {
                    val right = pixels[y][x + 1] != '.' || (x + 2 < width && pixels[y][x + 2] != '.')
                    if (pixels[y][x] == '.' && pixels[y][x - 1] != '.' && right) pixels[y][x] = 'F'
                }
            }
        }

        /** A leg two pixels wide, hanging down to its claws at [foot]. */
        fun leg(
            x: Int,
            foot: Int,
        ) {
            for (y in foot - 3..foot) {
                this[x, y] = if (y == foot) 'C' else 'F'
                this[x + 1, y] = if (y == foot) 'C' else 'F'
            }
        }

        /** The lines to print: two pixels in each character, with the colors of a 24-bit terminal. */
        fun render(): List<String> =
            (0 until height step 2).map { y ->
                (0 until width).joinToString("") { x ->
                    val letter = texts[x to y / 2]?.takeIf { pixels[y][x] == '.' && pixels[y + 1][x] == '.' }
                    if (letter != null) "$RESET${fg(colors.getValue('z'))}$letter" else cell(colors[pixels[y][x]], colors[pixels[y + 1][x]])
                } + RESET
            }
    }

    private val colors =
        mapOf(
            'b' to 0xB07440, // branch
            'B' to 0x6E4528, // branch, shadow
            'C' to 0xE2B07A, // claws
            'F' to 0x9C7A5B, // fur
            'd' to 0x6F5440, // fur, dark
            'M' to 0xE9DCC0, // face
            'E' to 0x4A2F1C, // the dark stripes that run down from the eyes, and closed eyes
            'o' to 0x15131F, // eyes
            'N' to 0x2A1E16, // nose
            'm' to 0x6E4528, // smile
            'O' to 0x3A1E1A, // open mouth
            'P' to 0xD98A8A, // tongue
            'L' to 0xC9A27E, // belly
            'G' to 0x6B9A3E, // leaf
            'g' to 0x48702A, // leaf, dark
            'z' to 0xEFE6D2, // the z's of its sleep
        )

    private fun cell(
        top: Int?,
        bottom: Int?,
    ): String =
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
