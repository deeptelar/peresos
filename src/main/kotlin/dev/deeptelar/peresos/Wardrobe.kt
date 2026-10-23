package dev.deeptelar.peresos

import kotlin.math.ceil
import kotlin.math.floor

/**
 * What Peresós can wear, chosen with `peresos wear`. Each item draws itself relative to the sloth's
 * head or branch, so one drawing fits every phase, both poses and every frame.
 *
 * To add an item: add an entry here, a branch to [wear], and a function that draws it below.
 * `./gradlew test` checks it in every phase and pose.
 */
enum class Item(val slot: Slot, val description: String) {
    SUNGLASSES(Slot.EYES, "dark glasses, also while it sleeps"),
    CAP(Slot.HEAD, "a navy cap, brim to the side"),
    BARRETINA(Slot.HEAD, "the red Catalan cap, its long tip flopped over"),
    COFFEE(Slot.BRANCH, "a mug of coffee on the branch, still steaming"),
}

/** Where an item goes. It wears one item in each slot, so a new hat replaces the old one. */
enum class Slot { HEAD, EYES, BRANCH }

/** Rows above the branch, or columns beside it, that an item needs outside the sloth's own picture. */
internal val Item.room: Int get() = if (this == Item.COFFEE) 6 else 0

/**
 * Where things are on one drawing of the sloth.
 *
 * @property cx the face is drawn around this column, between cx - 1 and cx.
 * @property eyes how many columns the eyes are from the middle of the face.
 * @property branch the top row of the branch.
 * @property shelf the first of five columns of branch with nothing on them, to put something on.
 * @property tick which frame of the animation this is, for things that move.
 */
internal class Spot(
    val cx: Int,
    val head: Oval,
    val eyes: Int,
    val branch: Int,
    val shelf: Int,
    val tick: Int,
) {
    /** The row of the eyes. */
    val eye: Int get() = head.y.toInt()
}

internal fun Canvas.wear(
    item: Item,
    at: Spot,
) = when (item) {
    Item.SUNGLASSES -> sunglasses(at)
    Item.CAP -> cap(at)
    Item.BARRETINA -> barretina(at)
    Item.COFFEE -> coffee(at)
}

private fun Canvas.sunglasses(at: Spot) {
    val lens = at.cx - 2 - at.eyes // the outer column of the left lens, which is two pixels wide and two tall
    for (x in lens..at.cx - 1) both(x, at.eye, if (x <= lens + 1) GLASS else FRAME, at.cx)
    for (x in lens..lens + 1) both(x, at.eye + 1, GLASS, at.cx)
    both(lens, at.eye, GLINT, at.cx)
    for (x in ceil(at.cx - at.head.rx).toInt() until lens) both(x, at.eye, FRAME, at.cx)
}

private fun Canvas.cap(at: Spot) {
    val last = crown(at, NAVY)
    for (x in at.cx - 1..ceil(at.cx + at.head.rx).toInt() + 1) this[x, last] = NAVY_DARK // the brim, turned to the side
    val top = floor(at.head.y - at.head.ry).toInt()
    this[at.cx - 1, top] = NAVY_DARK
    this[at.cx, top] = NAVY_DARK
}

private fun Canvas.barretina(at: Spot) {
    val last = crown(at, RED)
    // The long tip flops over to the right, and hangs down beside the head to the eyes.
    val top = floor(at.head.y - at.head.ry).toInt()
    val side = ceil(at.cx + at.head.rx).toInt()
    for (x in at.cx..side) {
        val y = top + (x - at.cx) * (last - top) / maxOf(side - at.cx, 1)
        this[x, y] = RED
        this[x, y + 1] = RED
    }
    for (y in last..at.eye + 1) {
        this[side, y] = RED
        this[side + 1, y] = if (y == at.eye + 1) RED_DARK else RED
    }
    this[side, at.eye + 1] = RED_DARK
    crown(at, BAND, from = last)
}

/** Covers the top of the head, from row [from] down to a little above the eyes, and returns the last row it covered. */
private fun Canvas.crown(
    at: Spot,
    rgb: Int,
    from: Int = floor(at.head.y - at.head.ry).toInt(),
): Int {
    val last = at.eye - 1 - (at.head.ry / 3).toInt()
    for (y in from..last) {
        for (x in 0 until width) if (at.head.contains(at.cx.toDouble(), x, y, grow = 0.4)) this[x, y] = rgb
    }
    return last
}

private fun Canvas.coffee(at: Spot) {
    val x = at.shelf
    val floor = at.branch - 1 // the mug stands on the branch
    for (y in floor - 2..floor) for (dx in 0..3) this[x + dx, y] = MUG
    this[x + 1, floor - 2] = COFFEE
    this[x + 2, floor - 2] = COFFEE
    this[x + 1, floor - 1] = RED
    this[x + 2, floor - 1] = RED
    this[x + 4, floor - 2] = MUG // the handle
    this[x + 4, floor - 1] = MUG
    // The steam sways a little from frame to frame.
    val sway = at.tick % 2
    this[x + 1 + sway, floor - 3] = STEAM
    this[x + 2 - sway, floor - 4] = STEAM
    this[x + 1 + sway, floor - 5] = STEAM
}

private const val GLASS = 0x1B1B24
private const val FRAME = 0x3A3A48
private const val GLINT = 0x8FA3C0
private const val NAVY = 0x23336B
private const val NAVY_DARK = 0x18244F
private const val RED = 0xA8322D
private const val RED_DARK = 0x7E2420
private const val BAND = 0x1E1A24
private const val MUG = 0xEFE6D2
private const val COFFEE = 0x4A2F1C
private const val STEAM = 0x8C8478
