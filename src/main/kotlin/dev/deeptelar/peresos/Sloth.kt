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
) {
    /** How round it is: one more for every item that left the pile for good. */
    val roundness: Int get() = meals.count { it.answer != Answer.LATER }
}

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
 * the person's answer, swallow, and write a line of the diary.
 *
 * @param model prepares the finished version of an item. It may think as long as it likes.
 * @param diarist writes the diary line, a small job: a model that does not think is enough.
 * @param hungry eat even if it already ate today, for a demo.
 * @param about what the person told it about themselves, such as which days they work from home.
 */
fun sloth(
    model: ChatModel,
    pile: Pile,
    hungry: Boolean = false,
    diarist: ChatModel = model,
    about: String = "",
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
                )
            }

        val chew =
            node(
                "chew",
                work = { sloth -> model.ask<Bite>(chewPrompt(sloth, about), system = CHEF) },
                retry = Retry(attempts = 2),
            ) { sloth, bite -> sloth.copy(bite = bite) }

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
                            pile.keep(sloth.today, bite)
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
                work = { sloth -> diarist.chat(diaryPrompt(sloth), system = VOICE) },
            ) { sloth, line -> sloth.copy(diary = sloth.diary + DiaryEntry(sloth.today, line.trim()), item = null) }

        START then wake
        conditionalEdge(wake, targets = setOf(chew, NodeRef.END)) { sloth -> if (sloth.rest == Rest.NONE) chew else NodeRef.END }
        chew then swallow
        conditionalEdge(swallow, targets = setOf(chew, diary)) { sloth -> if (sloth.bite == null) chew else diary }
        diary then NodeRef.END
    }.compile()

private fun chewPrompt(sloth: Sloth, about: String): String {
    val tried =
        if (sloth.tries.isEmpty()) {
            ""
        } else {
            "\n\nI did not want these versions, so make a different one:\n" + sloth.tries.joinToString("\n") { "- ${it.replace("\n", " ")}" }
        }
    val me = if (about.isBlank()) "" else "What you know about me:\n${about.trim()}\n\n"
    return "${me}Here is an item of my backlog. Make it done for me.\n\n${sloth.item}$tried"
}

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
    val last = if (sloth.emptied) "It was the last item: the pile is empty now, you ate everything, and you can sleep on top of your branch. " else ""
    return "Write today's line of your diary: one or two short sentences, in the first person, no more than 30 words. " +
        "${sleep}Today you ate one item of the pile, a ${meal.kind.name.lowercase()} called \"${meal.title}\", and $said. " +
        "${last}You have eaten ${sloth.roundness} items in all. Reply with the line only."
}
