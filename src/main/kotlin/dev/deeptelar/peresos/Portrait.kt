package dev.deeptelar.peresos

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The sloth as pixel art, hanging from its branch. It is born a baby and grows bigger and rounder
 * with every item it finishes, and in each phase it does something different while you look. When
 * it has eaten the whole pile, it climbs on top of its branch and sleeps there. It can wear the
 * items of the [Item] wardrobe.
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
        wearing: Collection<Item> = emptyList(),
    ): List<String> = frames(roundness, onTop, wearing).first()

    /**
     * The frames of the animation, to show one after another and then again. All have the same size.
     *
     * @param onTop it ate the whole pile, and sleeps lying on top of its branch instead of hanging below it.
     * @param wearing the items it has on.
     */
    fun frames(
        roundness: Int,
        onTop: Boolean = false,
        wearing: Collection<Item> = emptyList(),
    ): List<List<String>> = drawings(roundness, onTop, wearing).map { (canvas, _) -> canvas.render() }

    /** The same frames as [frames], as pixels for a window. */
    internal fun pictures(
        roundness: Int,
        onTop: Boolean = false,
        wearing: Collection<Item> = emptyList(),
    ): List<Picture> = drawings(roundness, onTop, wearing).map { (canvas, branch) -> canvas.picture(branch) }

    /**
     * The sloth while someone touches it, for as long as it reacts: a pat on the head closes its eyes,
     * a tickle on the belly makes it laugh and kick, a poke opens its eyes wide. Fed up, it shuts them.
     */
    internal fun touched(
        roundness: Int,
        onTop: Boolean,
        wearing: Collection<Item>,
        touch: Touch,
        fedUp: Boolean = false,
    ): Picture {
        val pose =
            when {
                fedUp || touch == Touch.HEAD -> Pose(closed = true)
                touch == Touch.BELLY -> Pose(mouth = 2, kick = 1)
                else -> Pose(mouth = 1)
            }
        return drawings(roundness, onTop, wearing, listOf(pose)).single().let { (canvas, branch) -> canvas.picture(branch) }
    }

    /** Which part of the sloth a pixel of its picture is, or `null` for its branch and the air around it. */
    internal fun part(rgb: Int): Touch? =
        when (rgb) {
            EMPTY, BRANCH, BRANCH_SHADOW -> null
            BELLY -> Touch.BELLY
            FACE, FUR_DARK, EYE, STRIPE, NOSE, MOUTH, TONGUE -> Touch.HEAD
            else -> Touch.FUR
        }

    /** What the sloth is and does, to write under its portrait. */
    fun caption(
        roundness: Int,
        grew: Boolean = false,
        onTop: Boolean = false,
    ): String {
        val finished = "$roundness ${if (roundness == 1) "item" else "items"} finished"
        val phase = phase(roundness)
        val doing = if (onTop) "asleep on top of its branch, full" else phase.doing
        return "${if (grew) "It grew! Peresós is now" else "Peresós is"} ${phase.title}, $doing ($finished)."
    }

    /** Each frame, with the row of the top of its branch; or one for each of [only], to show one face. */
    private fun drawings(
        roundness: Int,
        onTop: Boolean,
        wearing: Collection<Item>,
        only: List<Pose>? = null,
    ): List<Pair<Canvas, Int>> {
        val build = builds.getValue(phase(roundness))
        val room = wearing.maxOfOrNull { it.room } ?: 0
        val items = wearing.sortedBy { it.ordinal }
        if (onTop) return (only ?: asleep).mapIndexed { tick, pose -> drawOnTop(build, pose, tick, items, room) to build.height - 5 }
        return (only ?: poses.getValue(phase(roundness))).mapIndexed { tick, pose -> draw(build, pose, tick, items, room) to 0 }
    }

    /** The size of the picture, and of the head and the body in it, in pixels. Both ovals are around the middle column. */
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

    /** Hanging below the branch. Items that need room get it above the branch, where a mug can stand. */
    private fun draw(
        build: Build,
        pose: Pose,
        tick: Int,
        items: List<Item>,
        room: Int,
    ): Canvas {
        val canvas = Canvas(build.width, build.height, top = room)
        val cx = build.width / 2 // the face sits on the two middle columns, cx - 1 and cx
        val head = build.head
        val body = build.body
        val breath = pose.breath * 0.5

        canvas.row(0, BRANCH)
        canvas.row(1, BRANCH_SHADOW)
        // The arms reach up from the shoulders to the branch, where the claws hold on.
        val claw = floor(cx - head.rx).toInt() - build.arm
        canvas.arm(claw, 1, floor(cx - body.rx * 0.8).toInt(), (body.y - body.ry * 0.5).toInt(), build.arm)
        repeat(build.arm) { canvas.both(claw + it, 1, CLAW) }
        // Two short legs hang below. A baby kicks them, one down while the other goes up.
        val foot = floor(body.y + body.ry).toInt() + 1
        val leg = floor(cx - body.rx * 0.5).toInt() - 1
        canvas.leg(leg, foot + pose.kick)
        canvas.leg(build.width - 2 - leg, foot - pose.kick)

        canvas.oval(cx.toDouble(), Oval(body.y, body.rx + breath, body.ry + breath), FUR)
        canvas.oval(cx.toDouble(), Oval(body.y + 0.5, body.rx * 0.75 + breath, body.ry * 0.75 + breath), BELLY, over = FUR)
        canvas.closeGaps(head.y.toInt(), body.y.toInt())
        canvas.head(cx, head)
        canvas.face(cx, head, build.eyes, pose)
        canvas.z(floor(cx + head.rx).toInt() + build.arm + 2, head.y.toInt() / 2, pose)
        val spot = Spot(cx, head, build.eyes, branch = 0, shelf = build.width - 6, tick = tick)
        items.forEach { canvas.wear(it, spot) }
        return canvas
    }

    /**
     * Lying on its belly on top of the branch, with its arms and legs hanging down on both sides of it.
     * Items that need room get it to the right, on a longer branch.
     */
    private fun drawOnTop(
        build: Build,
        pose: Pose,
        tick: Int,
        items: List<Item>,
        room: Int,
    ): Canvas {
        val canvas = Canvas(build.width, build.height, right = room)
        val branch = build.height - 5 // also in drawings
        val hx = build.width - ceil(build.head.rx).toInt() // the head at the right end, the face on columns hx - 1 and hx
        val head = Oval(branch - build.head.ry + 1, build.head.rx, build.head.ry)
        val length = build.body.ry * 1.3
        val tall = build.body.rx * 0.8 + pose.breath * 0.5
        val bodyX = maxOf(length + 0.5, hx - head.rx - length + 2)

        canvas.row(branch, BRANCH)
        canvas.row(branch + 1, BRANCH_SHADOW)
        val leg = (bodyX - length * 0.5).toInt()
        for (x in listOf(leg, leg + build.arm + 1, hx - 2 - build.arm, hx + 2)) {
            for (y in branch - 1..branch + 3) repeat(build.arm) { canvas[x + it, y] = if (y == branch + 3) CLAW else FUR }
        }
        canvas.oval(bodyX, Oval(branch + 0.3 - tall, length, tall), FUR)
        canvas.head(hx, head)
        canvas.face(hx, head, build.eyes, pose)
        canvas.z(hx + 1, floor(head.y - head.ry).toInt() / 2 - 1, pose)
        val spot = Spot(hx, head, build.eyes, branch = branch, shelf = build.width + 1, tick = tick)
        items.forEach { canvas.wear(it, spot) }
        return canvas
    }

    /** The head around column [cx]: fur, darker on top, and the pale mask of the face. */
    private fun Canvas.head(
        cx: Int,
        head: Oval,
    ) {
        oval(cx.toDouble(), head, FUR)
        oval(cx.toDouble(), Oval(head.y - 0.5, head.rx - 1, head.ry - 1), FUR_DARK, over = FUR)
        oval(cx.toDouble(), Oval(head.y + 0.5, head.rx - 1, head.ry - 1.2), FACE, over = FUR_DARK)
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
        both(eyeX, eye, if (pose.closed) STRIPE else EYE, cx)
        both(eyeX - 1, eye, STRIPE, cx)
        both(eyeX - 2, eye + 1, STRIPE, cx)
        both(cx - 1, eye + 1, NOSE, cx)
        val mouth = eye + 2
        when (pose.mouth) {
            0 -> {
                both(cx - 2, mouth, SMILE, cx)
                both(cx - 1, mouth + 1, SMILE, cx)
            }
            1 -> {
                both(cx - 2, mouth, SMILE, cx)
                both(cx - 1, mouth, MOUTH, cx)
            }
            else -> {
                both(cx - 1, mouth, MOUTH, cx)
                both(cx - 1, mouth + 1, TONGUE, cx)
            }
        }
        repeat(pose.leaf) { this[cx + 1 + it, mouth + 1] = LEAF }
        if (pose.leaf == 3) this[cx + 3, mouth] = LEAF_DARK
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

    /** A left arm from the claw down to the shoulder, and the right one mirrored. */
    private fun Canvas.arm(
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
            repeat(thickness) { both(x + it, y, FUR) }
        }
    }

    /** Fills the holes of one or two pixels between an arm and the body, in rows [fromY] to [toY]. */
    private fun Canvas.closeGaps(
        fromY: Int,
        toY: Int,
    ) {
        for (y in fromY..toY) {
            for (x in 1 until width - 1) {
                val right = this[x + 1, y] != EMPTY || this[x + 2, y] != EMPTY
                if (this[x, y] == EMPTY && this[x - 1, y] != EMPTY && right) this[x, y] = FUR
            }
        }
    }

    /** A leg two pixels wide, hanging down to its claws at [foot]. */
    private fun Canvas.leg(
        x: Int,
        foot: Int,
    ) {
        for (y in foot - 3..foot) {
            this[x, y] = if (y == foot) CLAW else FUR
            this[x + 1, y] = if (y == foot) CLAW else FUR
        }
    }

    internal const val BRANCH = 0xB07440
    internal const val BRANCH_SHADOW = 0x6E4528
    internal const val LEAF = 0x6B9A3E
    internal const val LEAF_DARK = 0x48702A
    private const val CLAW = 0xE2B07A
    private const val FUR = 0x9C7A5B
    private const val FUR_DARK = 0x6F5440
    private const val FACE = 0xE9DCC0
    private const val STRIPE = 0x4A2F1C // the dark stripes that run down from the eyes, and closed eyes
    private const val EYE = 0x15131F
    private const val NOSE = 0x2A1E16
    private const val SMILE = 0x6E4528
    private const val MOUTH = 0x3A1E1A
    private const val TONGUE = 0xD98A8A
    private const val BELLY = 0xC9A27E
}
