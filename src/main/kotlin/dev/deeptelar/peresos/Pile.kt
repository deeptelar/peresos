package dev.deeptelar.peresos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The backlog the sloth eats from: the oldest item first. */
interface Pile {
    fun items(): List<String>

    fun add(item: String)

    fun remove(item: String)

    fun moveToEnd(item: String)

    /** Keeps the finished version of an item that the person took. */
    fun keep(day: String, bite: Bite)
}

/**
 * A pile in a plain text file that the person may edit by hand: items are separated by a line with
 * `---`. What the person took goes into [done], one Markdown file per item.
 */
class FilePile(private val file: Path, private val done: Path) : Pile {
    override fun items(): List<String> =
        if (file.exists()) file.readText().split(BETWEEN).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    override fun add(item: String) = write(items() + item.trim())

    override fun remove(item: String) {
        val items = items()
        val at = items.indexOf(item.trim())
        if (at >= 0) write(items.filterIndexed { index, _ -> index != at })
    }

    override fun moveToEnd(item: String) {
        val items = items()
        if (item.trim() in items) write(items - item.trim() + item.trim())
    }

    override fun keep(day: String, bite: Bite) {
        done.createDirectories()
        val slug = bite.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40)
        done.resolve("$day-$slug.md").writeText("# ${bite.title}\n\n${bite.kind.name.lowercase()}, $day\n\n${bite.finished.trim()}\n")
    }

    private fun write(items: List<String>) {
        file.parent?.let { Files.createDirectories(it) }
        file.writeText(items.joinToString(SEPARATOR, postfix = if (items.isEmpty()) "" else "\n"))
    }

    private companion object {
        const val SEPARATOR = "\n---\n"

        /** A line with `---` alone, also one the person typed with spaces around it. */
        val BETWEEN = Regex("(?m)^\\s*---\\s*$")
    }
}
