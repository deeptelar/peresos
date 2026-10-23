package dev.deeptelar.peresos

import dev.deeptelar.telar.GraphSnapshot

/** Where the person touched the sloth in the habitat. */
internal enum class Touch { HEAD, BELLY, FUR }

/** What it is doing when touched, which is what it talks about. */
internal enum class Mood { SLEEPY, ATE, FULL, CHEWING, WAITING, BUSY, TROUBLE }

/** After this many touches in a row, it pretends to sleep. */
internal const val FED_UP = 6

/** How long a reaction lasts, and how long after it a new touch still counts as one more in a row. */
internal const val REACTION_MS = 3_000L
internal const val IN_A_ROW_MS = 5_000L

/**
 * How it reacts to a touch: what it says, and whether it pretends to sleep.
 *
 * @param count the touches in a row, this one included.
 * @param tick the tick of the animations when it was touched, to wiggle from there.
 * @param at when it was touched, in milliseconds.
 */
internal class Reaction(
    val touch: Touch,
    val line: String,
    val count: Int,
    val tick: Int,
    val at: Long,
) {
    val fedUp: Boolean get() = count >= FED_UP
}

internal fun feeling(
    model: SlothModel,
    snapshot: GraphSnapshot<Sloth>,
): Mood {
    val sloth = snapshot.state
    return when {
        model.busy -> Mood.BUSY
        snapshot is GraphSnapshot.Running -> Mood.CHEWING
        snapshot is GraphSnapshot.Interrupted && SWALLOW in snapshot.nextNodes && sloth?.bite != null -> Mood.WAITING
        snapshot is GraphSnapshot.Interrupted || snapshot is GraphSnapshot.Stopped -> Mood.TROUBLE
        model.isFull(sloth) -> Mood.FULL
        model.ateToday(sloth) -> Mood.ATE
        else -> Mood.SLEEPY
    }
}

/**
 * What it says when touched: a pat on the head, a tickle on the belly or a poke anywhere else, and
 * what it is doing. Each touch in a row gets the next line, until it is fed up.
 *
 * @param count the touches in a row, this one included.
 * @param pile how many items are on the pile.
 */
internal fun said(
    touch: Touch,
    mood: Mood,
    count: Int,
    pile: Int,
): String {
    if (count >= FED_UP) return listOf("Zzz. (It pretends to sleep.)", "...", "Zzz. Zzz. (Very convincing.)")[(count - FED_UP) % 3]
    val lines =
        when {
            touch == Touch.HEAD && mood == Mood.CHEWING -> listOf("Mmf. Pat now, swallow later.", "Mmm. Chewing and a pat. Perfect day.")
            touch == Touch.HEAD -> listOf("Mmm. Behind the ears, please.", "Slower. Slower. Perfect.", "I could fall asleep like this. I will.")
            touch == Touch.BELLY && mood == Mood.FULL -> listOf("Careful! Everything I ate is in there.", "Hee! Too full to laugh properly.")
            touch == Touch.BELLY -> listOf("Hee hee! Not the belly!", "That tickles! Slowly, but it tickles.", "Stop, stop! I'll let go of the branch!")
            else ->
                when (mood) {
                    Mood.SLEEPY -> listOf("Mmh? Five more minutes.", "$pile on the pile. None of them on fire.", "Wake me with the button. Gently.")
                    Mood.ATE -> listOf("One a day. I had mine.", "Come back tomorrow. Bring something boring.")
                    Mood.FULL -> listOf("Too full to move.", "I ate everything. Let me digest. For a week.")
                    Mood.CHEWING -> listOf("Mmf. Mouth full.", "Poking won't make me chew faster.", "If anything, slower.")
                    Mood.WAITING -> listOf("It's still in my mouth. Take it or bin it?", "Your turn. I'm good at waiting.")
                    Mood.BUSY -> listOf("Shh. I'm talking to the terminal.", "One person at a time.")
                    Mood.TROUBLE -> listOf("My head is foggy. Try again below?", "Something went wrong. Not my fault. Probably.")
                }
        }
    return lines[(count - 1) % lines.size]
}
