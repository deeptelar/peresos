package dev.deeptelar.peresos

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The sloth as pixel art, hanging from its branch. It is born a baby and grows bigger and rounder
 * with every item it finishes, and in each phase it does something different while you look. When
 * it has eaten the whole pile, it climbs on top of its branch and sleeps there.
 */
object Portrait {
    /** How grown up the sloth is, and what it does in its animation. */
    enum class Phase(val title: String, val doing: String) {
        BABY("a baby", "kicking its little legs"),
        YOUNG("young", "chewing a leaf"),
        GROWN("grown up", "yawning"),
        ROUND("big and round", "asleep"),
    }

    fun phase(roundness: Int): Phase =
        when {
            roundness < 2 -> Phase.BABY
            roundness < 5 -> Phase.YOUNG
            roundness < 10 -> Phase.GROWN
            else -> Phase.ROUND
        }

    /** The portrait standing still: the first frame of its animation. */
    fun lines(
        roundness: Int,
        onTop: Boolean = false,
    ): List<String> = frames(roundness, onTop).first()

    /**
     * The frames of the animation, to show one after another and then again. All have the same size.
     *
     * @param onTop it ate the whole pile, and sleeps lying on top of its branch instead of hanging below it.
     */
    fun frames(
        roundness: Int,
        onTop: Boolean = false,
    ): List<List<String>> {
        val build = builds.getValue(phase(roundness))
        if (onTop) return asleep.map { pose -> drawOnTop(build, pose).render() }
        return poses.getValue(phase(roundness)).map { pose -> draw(build, pose).render() }
    }

    /** An oval around a column, at height [y]. */
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

    /** Fast asleep and breathing slowly, with the z's floating up. */
    private val asleep = poses.getValue(Phase.ROUND)

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
        canvas.head(cx, head)
        canvas.face(cx, head, build.eyes, pose)
        canvas.z(floor(cx + head.rx).toInt() + build.arm + 2, head.y.toInt() / 2, pose)
        return canvas
    }

    /** Lying on its belly on top of the branch, with its arms and legs hanging down on both sides of it. */
    private fun drawOnTop(
        build: Build,
        pose: Pose,
    ): Canvas {
        val canvas = Canvas(build.width, build.height)
        val branch = build.height - 5
        val hx = build.width - ceil(build.head.rx).toInt() // the head at the right end, the face on columns hx - 1 and hx
        val head = Oval(branch - build.head.ry + 1, build.head.rx, build.head.ry)
        val length = build.body.ry * 1.3
        val tall = build.body.rx * 0.8 + pose.breath * 0.5
        val bodyX = maxOf(length + 0.5, hx - head.rx - length + 2)

        canvas.row(branch, 'b')
        canvas.row(branch + 1, 'B')
        val leg = (bodyX - length * 0.5).toInt()
        for (x in listOf(leg, leg + build.arm + 1, hx - 2 - build.arm, hx + 2)) {
            for (y in branch - 1..branch + 3) repeat(build.arm) { canvas[x + it, y] = if (y == branch + 3) 'C' else 'F' }
        }
        canvas.oval(bodyX, branch + 0.3 - tall, length, tall, 'F')
        canvas.head(hx, head)
        canvas.face(hx, head, build.eyes, pose)
        canvas.z(hx + 1, floor(head.y - head.ry).toInt() / 2 - 1, pose)
        return canvas
    }

    /** The head around column [cx]: fur, darker on top, and the pale mask of the face. */
    private fun Canvas.head(
        cx: Int,
        head: Oval,
    ) {
        oval(cx.toDouble(), head.y, head.rx, head.ry, 'F')
        oval(cx.toDouble(), head.y - 0.5, head.rx - 1, head.ry - 1, 'd', over = 'F')
        oval(cx.toDouble(), head.y + 0.5, head.rx - 1, head.ry - 1.2, 'M', over = 'd')
    }

    /** The face around column [cx]: eyes in the dark stripes that run down from them, the nose, and the mouth. */
    private fun Canvas.face(
        cx: Int,
        head: Oval,
        eyes: Int,
        pose: Pose,
    ) {
        val eye = head.y.toInt()
        val eyeX = cx - 1 - eyes
        both(eyeX, eye, if (pose.closed) 'E' else 'o', cx)
        both(eyeX - 1, eye, 'E', cx)
        both(eyeX - 2, eye + 1, 'E', cx)
        both(cx - 1, eye + 1, 'N', cx)
        val mouth = eye + 2
        when (pose.mouth) {
            0 -> {
                both(cx - 2, mouth, 'm', cx)
                both(cx - 1, mouth + 1, 'm', cx)
            }
            1 -> {
                both(cx - 2, mouth, 'm', cx)
                both(cx - 1, mouth, 'O', cx)
            }
            else -> {
                both(cx - 1, mouth, 'O', cx)
                both(cx - 1, mouth + 1, 'P', cx)
            }
        }
        repeat(pose.leaf) { this[cx + 1 + it, mouth + 1] = 'G' }
        if (pose.leaf == 3) this[cx + 3, mouth] = 'g'
    }

    /** Asleep, the z's float up and to the right from column [x] and [line]. */
    private fun Canvas.z(
        x: Int,
        line: Int,
        pose: Pose,
    ) {
        if (pose.z < 0) return
        val stages = listOf(Triple(x, line, 'z'), Triple(x + 1, line - 1, 'z'), Triple(x + 1, line - 2, 'Z'), Triple(x + 2, line - 3, 'Z'))
        for (stage in listOf(pose.z % 4, (pose.z + 2) % 4)) stages[stage].let { (sx, sy, letter) -> if (sy >= 0) texts[sx to sy] = letter }
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

        /** A pixel and its mirror on the other side of column [center], which falls between [center] - 1 and [center]. */
        fun both(
            x: Int,
            y: Int,
            pixel: Char,
            center: Int = width / 2,
        ) {
            this[x, y] = pixel
            this[2 * center - 1 - x, y] = pixel
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
