package dev.deeptelar.peresos

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.ask
import dev.deeptelar.telar.agent.chat
import dev.deeptelar.telar.serialization.effect
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What an item of the pile turns out to be. */
@Serializable
enum class Kind { REPLY, DECISION, NOTE, TASK }

/** What Gemma makes of one item of the pile: the finished version, ready to use. */
@Serializable
data class Bite(
    @Description("REPLY for a message that waits for an answer, DECISION for a choice to make, NOTE for something to keep, TASK for something to do")
    val kind: Kind,
    @Description("A title of at most six words")
    val title: String,
    @Description(
        "The finished thing, ready to use as it is. For a REPLY: the whole reply, in the language of the message. " +
            "For a DECISION: the choice, then one or two sentences on why. For a NOTE: the note cleaned up, in a few lines. " +
            "For a TASK: the first step, which takes five minutes at most, and nothing more.",
    )
    val finished: String,
)

/** Which item of the pile is the easiest, for a lazy wake. */
@Serializable
data class Pick(
    @Description("The number of the easiest item in the list")
    val number: Int,
    @Description("Why it is the easiest, in one short sentence, as a lazy sloth would say it")
    val why: String,
)

/** What the person says to a [Bite]. */
@Serializable
enum class Answer { YES, NO, LATER, AGAIN }

/** An item the sloth ate on [day], and what the person said to it. */
@Serializable
data class Meal(val day: String, val title: String, val kind: Kind, val answer: Answer)

/** A line of the sloth's diary. */
@Serializable
data class DiaryEntry(val day: String, val text: String)

/** Why a wake ended without a meal. */
@Serializable
enum class Rest { NONE, ALREADY_ATE, EMPTY_PILE }

/**
 * The sloth. It lives in one checkpoint: everything it remembers from one day to the next is here.
 *
 * @property today the day of this wake, as `2026-10-23`.
 * @property sleptDays how many days passed since the wake before this one.
 * @property item the item of the pile it is eating, while it eats.
 * @property bite what Gemma made of [item], waiting for an [answer].
 * @property tries the versions of [item] the person sent back with [Answer.AGAIN].
 * @property emptied whether the meal of this wake was the last item of the pile: it ate everything.
 * @property lazy this wake picks the easiest item of the pile, not the oldest, and does it the laziest way.
 * @property why why it picked [item], on a lazy wake.
 * @property lastLazy the item the last lazy wake picked: the next one picks another, so an item
 * answered with "later" does not come back every time.
 * @property raw [bite] is the item as it was, brought without Gemma: there is nothing to try again.
 */
@Serializable
data class Sloth(
    val today: String,
    val lastWake: String? = null,
    val lastMeal: String? = null,
    val sleptDays: Int = 0,
    val rest: Rest = Rest.NONE,
    val item: String? = null,
    val bite: Bite? = null,
    val answer: Answer? = null,
    val meals: List<Meal> = emptyList(),
    val diary: List<DiaryEntry> = emptyList(),
    val tries: List<String> = emptyList(),
    val emptied: Boolean = false,
    val lazy: Boolean = false,
    val why: String? = null,
    val lastLazy: String? = null,
    val raw: Boolean = false,
) {
    /** How round it is: one more for every item that left the pile for good. */
    val roundness: Int get() = meals.count { it.answer != Answer.LATER }
}

private const val LAZY =
    " Make the laziest version that still gets it off the pile: a reply of one or two lines, the choice that needs no follow-up, " +
        "or a first step of two minutes. If honestly the laziest and best thing is not to do it at all, say so: " +
        "the finished version then starts with \"Don't do it.\" and gives one sentence why."

/** At most this many items of the pile go to Gemma when it looks for the easiest one. */
private const val LOOK_AT = 30

/** The name of the node that the run pauses before, until the person answers. */
const val SWALLOW = "swallow"

private const val CHEF =
    "You turn an item of someone's backlog into its finished version, so that they only have to say yes or no. " +
        "Write as that person, never as an assistant, and in the language of the item. Be short, warm and concrete. " +
        "Do not invent facts that the item does not give; when one is missing, leave a clear blank like [time]."

private const val VOICE =
    "You are Peresós, a sloth who lives in a terminal and eats one item of a backlog per day. " +
        "You are slow, content and never in a hurry, and you find hurry a little funny. You never nag."

/**
 * The graph of one wake: look at the pile, chew the oldest item into its finished version, wait for
 * the person's answer, swallow, and write a line of the diary. A [Sloth.lazy] wake first asks the
 * model which item is the easiest, and chews it the laziest way.
 *
 * @param model prepares the finished version of an item. It may think as long as it likes.
 * @param diarist writes the diary line, a small job: a model that does not think is enough.
 * @param hungry eat even if it already ate today, for a demo.
 * @param about reads what the person told it about themselves, such as which days they work from home,
 * each time it chews: the window lets them add facts while it runs.
 * @param gemma whether it uses Gemma, read at every step: without it, a lazy wake picks the shortest
 * item, chewing brings the item as it is, and the diary is written from the sloth's own lines. For a
 * computer without Ollama, as a PC of work that may not install it.
 */
fun sloth(
    model: ChatModel,
    pile: Pile,
    hungry: Boolean = false,
    diarist: ChatModel = model,
    about: () -> String = { "" },
    gemma: () -> Boolean = { true },
): CompiledGraph<Sloth> =
    StateGraph<Sloth> {
        val wake =
            node("wake") { sloth ->
                val slept = sloth.lastWake?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(sloth.today)).toInt() } ?: 0
                val next = pile.items().firstOrNull()
                val rest =
                    when {
                        sloth.lastMeal == sloth.today && !hungry -> Rest.ALREADY_ATE
                        next == null -> Rest.EMPTY_PILE
                        else -> Rest.NONE
                    }
                sloth.copy(
                    lastWake = sloth.today,
                    sleptDays = slept,
                    rest = rest,
                    item = next.takeIf { rest == Rest.NONE },
                    bite = null,
                    answer = null,
                    emptied = false,
                    why = null,
                )
            }

        val pick =
            node(
                "pick",
                work = { sloth ->
                    val items = pile.items()
                    // Another item than last time, unless there is no other.
                    val choices = items.filter { it != sloth.lastLazy }.ifEmpty { items }.take(LOOK_AT)
                    if (choices.size == 1) return@node Pick(1, "It is the only one there.") to choices
                    if (!gemma()) return@node Pick(choices.indexOf(choices.minBy { shown(it).length }) + 1, "It is the shortest one. Short things are quick to eat.") to choices
                    val pick = model.ask<Pick>(pickPrompt(choices), system = VOICE)
                    check(pick.number in 1..choices.size) { "Gemma picked number ${pick.number} of ${choices.size}." }
                    pick to choices
                },
                retry = Retry(attempts = 2),
            ) { sloth, (pick, choices) ->
                val item = choices[pick.number - 1]
                sloth.copy(item = item, why = pick.why.trim(), lastLazy = item)
            }

        val chew =
            node(
                "chew",
                work = { sloth ->
                    val item = checkNotNull(sloth.item)
                    if (!gemma()) return@node raw(pile.read(item))
                    // The photos and PDFs of the item are read from the pile here, never kept in the checkpoint.
                    val plate = pile.serve(item)
                    model.ask<Bite>(chewPrompt(sloth, plate.text, about()), system = if (sloth.lazy) CHEF + LAZY else CHEF, attachments = plate.attachments)
                },
                retry = Retry(attempts = 2),
            ) { sloth, bite -> sloth.copy(bite = bite, raw = !gemma()) }

        val swallow =
            node(SWALLOW) { sloth ->
                val item = checkNotNull(sloth.item)
                val bite = checkNotNull(sloth.bite)
                val answer = checkNotNull(sloth.answer) { "The sloth waits for an answer before it swallows." }
                // Another try: nothing is swallowed, and the item goes back to be chewed again.
                if (answer == Answer.AGAIN) return@node sloth.copy(tries = sloth.tries + bite.finished, bite = null, answer = null)
                // Once, also when this step runs again after a crash: the pile changes and the file is written one time.
                effect("swallow") {
                    when (answer) {
                        Answer.YES -> {
                            pile.keep(sloth.today, item, bite)
                            pile.remove(item)
                        }
                        Answer.NO -> pile.remove(item)
                        Answer.LATER -> pile.moveToEnd(item)
                        Answer.AGAIN -> Unit
                    }
                }
                sloth.copy(
                    lastMeal = sloth.today,
                    meals = sloth.meals + Meal(sloth.today, bite.title, bite.kind, answer),
                    tries = emptyList(),
                    emptied = answer != Answer.LATER && pile.items().isEmpty(),
                )
            }

        val diary =
            node(
                "diary",
                work = { sloth -> if (sloth.raw) ownLine(sloth) else diarist.chat(diaryPrompt(sloth), system = VOICE) },
            ) { sloth, line -> sloth.copy(diary = sloth.diary + DiaryEntry(sloth.today, line.trim()), item = null) }

        START then wake
        conditionalEdge(wake, targets = setOf(pick, chew, NodeRef.END)) { sloth ->
            when {
                sloth.rest != Rest.NONE -> NodeRef.END
                sloth.lazy -> pick
                else -> chew
            }
        }
        pick then chew
        chew then swallow
        conditionalEdge(swallow, targets = setOf(chew, diary)) { sloth -> if (sloth.bite == null) chew else diary }
        diary then NodeRef.END
    }.compile()

/** The item as it is, for a wake without Gemma: its first line is the title, and the whole of it what to do. */
private fun raw(item: String): Bite {
    val text = item.trim()
    val first = text.lines().first().trim()
    return Bite(Kind.TASK, if (first.length <= 48) first else first.take(47).trimEnd() + "…", text)
}

/** A line of the diary without Gemma: the sloth's own, picked by the day, so a day keeps its line. */
private fun ownLine(sloth: Sloth): String {
    val meal = sloth.meals.last()
    val lines =
        when {
            sloth.emptied -> OWN_EMPTIED
            meal.answer == Answer.YES -> OWN_DONE
            meal.answer == Answer.NO -> OWN_BINNED
            else -> OWN_LATER
        }
    return lines[Math.floorMod((sloth.today + meal.title).hashCode(), lines.size)].replace("{title}", meal.title)
}

private val OWN_DONE =
    listOf(
        "You did \"{title}\" yourself. I watched. Watching is also work, if you do it slowly.",
        "\"{title}\" is done, and I did not lift a claw. A fine day.",
        "I carried \"{title}\" all the way to you. You did the rest. Teamwork.",
        "\"{title}\": gone. I celebrated with a nap.",
    )

private val OWN_BINNED =
    listOf(
        "We spat out \"{title}\". Not every leaf is worth chewing.",
        "\"{title}\" went in the bin. Deciding not to is also finishing.",
        "Goodbye, \"{title}\". I will not miss you, and neither will you.",
    )

private val OWN_LATER =
    listOf(
        "\"{title}\" went back under the pile. It will keep. Most things do.",
        "Later, said you. Later, said I. We agree on everything.",
        "I put \"{title}\" back, slowly. There is always tomorrow, and the day after.",
    )

private val OWN_EMPTIED =
    listOf(
        "The pile is empty. I ate everything, without chewing once. Now I sleep on top of my branch.",
        "Nothing left to eat. I shall nap on my branch until you feed me again.",
    )

private fun chewPrompt(sloth: Sloth, item: String, about: String): String {
    val tried =
        if (sloth.tries.isEmpty()) {
            ""
        } else {
            "\n\nI did not want these versions, so make a different one:\n" + sloth.tries.joinToString("\n") { "- ${it.replace("\n", " ")}" }
        }
    val me = if (about.isBlank()) "" else "What you know about me:\n${about.trim()}\n\n"
    return "${me}Here is an item of my backlog. Make it done for me.\n\n$item$tried"
}

private fun pickPrompt(items: List<String>): String =
    "Here is my backlog. Which item is the easiest to get off it, with the least effort? Reply with its number and why.\n\n" +
        items.mapIndexed { index, item -> "${index + 1}. ${shown(item).replace("\n", " ").take(300)}" }.joinToString("\n")

private fun diaryPrompt(sloth: Sloth): String {
    val meal = sloth.meals.last()
    val said =
        when (meal.answer) {
            Answer.YES -> "they took it, so it is off the pile for good"
            Answer.NO -> "they said no, so it went in the bin, which is also a way to finish something"
            Answer.LATER -> "they said later, so you put it back at the bottom of the pile"
            Answer.AGAIN -> error("A meal is never an AGAIN.")
        }
    val sleep = if (sloth.sleptDays > 1) "You slept ${sloth.sleptDays} days before this wake. " else ""
    val lazy = if (sloth.why != null) "You picked it yourself, the easiest one on the pile, and did it the lazy way. " else ""
    val last = if (sloth.emptied) "It was the last item: the pile is empty now, you ate everything, and you can sleep on top of your branch. " else ""
    return "Write today's line of your diary: one or two short sentences, in the first person, no more than 30 words. " +
        "${sleep}Today you ate one item of the pile, a ${meal.kind.name.lowercase()} called \"${meal.title}\", and $said. " +
        "$lazy${last}You have eaten ${sloth.roundness} items in all. Reply with the line only."
}
