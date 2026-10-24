package dev.deeptelar.peresos

import dev.deeptelar.telar.agent.Attachment
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The backlog the sloth eats from: the oldest item first. */
interface Pile {
    fun items(): List<String>

    fun add(item: String)

    fun remove(item: String)

    fun moveToEnd(item: String)

    /** Keeps the finished version of an [item] that the person took. */
    fun keep(day: String, item: String, bite: Bite)

    /** The [item] as it goes to Gemma: its text, and the files it holds. */
    fun serve(item: String): Plate = Plate(item)
}

/**
 * An item of the pile as Gemma gets it: [text] says what the item is, and [attachments] are the
 * photos it holds, or the pages of a PDF that has no text, for Gemma to look at.
 */
class Plate(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

/**
 * A pile in a plain text file that the person may edit by hand: items are separated by a line with
 * `---`. What the person took goes into [done], one Markdown file per item.
 *
 * A photo or a PDF fed to it is copied into `files`, next to [file], and its item is a link to the
 * copy, in Markdown: `![receipt.jpg](files/20261024-153000-receipt.jpg)` for a photo. The copy goes
 * when its item leaves the pile, into [done] if the person took it.
 */
class FilePile(val file: Path, val done: Path) : Pile {
    private val folder: Path = file.toAbsolutePath().parent

    override fun items(): List<String> =
        if (file.exists()) file.readText().split(BETWEEN).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    override fun add(item: String) = write(items() + item.trim())

    /**
     * Puts the file at [source] on the pile, and returns its item: a photo or a PDF is copied in, so
     * the original can move; a text file gives its text.
     *
     * @throws IllegalArgumentException when Peresós does not eat files of its type.
     */
    fun addFile(source: Path): String {
        val name = source.name
        val item =
            when (val food = Food.of(source)) {
                Food.TEXT -> source.readText().trim().take(Food.MAX_TEXT).ifEmpty { throw IllegalArgumentException("$name is empty.") }
                null -> throw IllegalArgumentException("Peresós eats photos (JPEG, PNG, GIF, WebP), PDFs and text files, and $name is none of them.")
                else -> {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    val files = folder.resolve(FILES).createDirectories()
                    val stored = generateSequence(0) { it + 1 }.map { n -> "$stamp${if (n == 0) "" else "-$n"}-${name.replace(UNSAFE, "-")}" }.first { !files.resolve(it).exists() }
                    source.copyTo(files.resolve(stored))
                    "${if (food == Food.PHOTO) "!" else ""}[${name.replace("]", "")}]($FILES/$stored)"
                }
            }
        add(item)
        return item
    }

    override fun remove(item: String) {
        val items = items()
        val at = items.indexOf(item.trim())
        if (at < 0) return
        write(items.filterIndexed { index, _ -> index != at })
        // Unless the same file is on the pile again.
        val left = items().flatMap { links(it) }.map { it.path }.toSet()
        links(item).filter { it.path !in left }.forEach { folder.resolve(it.path).deleteIfExists() }
    }

    override fun moveToEnd(item: String) {
        val items = items()
        if (item.trim() in items) write(items - item.trim() + item.trim())
    }

    override fun keep(day: String, item: String, bite: Bite) {
        done.createDirectories()
        val slug = bite.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40)
        // The photo or the PDF it was made from, next to it.
        val from =
            links(item).filter { folder.resolve(it.path).exists() }.joinToString("") { link ->
                val copy = "$day-$slug-${link.name.replace(UNSAFE, "-")}"
                folder.resolve(link.path).copyTo(done.resolve(copy), overwrite = true)
                "\nFrom ${if (link.photo) "!" else ""}[${link.name}]($copy)\n"
            }
        done.resolve("$day-$slug.md").writeText("# ${bite.title}\n\n${bite.kind.name.lowercase()}, $day\n\n${bite.finished.trim()}\n$from")
    }

    /**
     * The item with its photos attached, and with the text of its PDFs; a PDF with no text, a scan,
     * is attached as pictures of its first pages.
     */
    override fun serve(item: String): Plate {
        val attachments = mutableListOf<Attachment>()
        val text =
            LINK.replace(item) { match ->
                val link = Link(match)
                val path = folder.resolve(link.path)
                when {
                    !path.exists() -> "the file ${link.name}, which is no longer there"
                    link.photo -> {
                        attachments += Attachment.of(path.readBytes(), Food.mediaType(path))
                        "the photo ${link.name}, attached"
                    }
                    else ->
                        when (val pdf = readPdf(path)) {
                            is Pdf.Text -> "the PDF ${link.name}, which says:\n\"\"\"\n${pdf.text}\n\"\"\"\n"
                            is Pdf.Pages -> {
                                attachments += pdf.pages.map { Attachment.of(it, "image/png") }
                                "the PDF ${link.name}, a scan: ${if (pdf.pages.size == 1) "its page is attached as a picture" else "its first ${pdf.pages.size} pages are attached as pictures"}"
                            }
                            Pdf.Locked -> "the PDF ${link.name}, which is locked with a password, so its text cannot be read"
                        }
                }
            }
        return Plate(text, attachments)
    }

    private fun write(items: List<String>) {
        file.parent?.let { Files.createDirectories(it) }
        file.writeText(items.joinToString(SEPARATOR, postfix = if (items.isEmpty()) "" else "\n"))
    }

    private companion object {
        const val SEPARATOR = "\n---\n"
        const val FILES = "files"

        /** A line with `---` alone, also one the person typed with spaces around it. */
        val BETWEEN = Regex("(?m)^\\s*---\\s*$")

        /** What a name of a file may not keep, to stay a plain link in Markdown. */
        val UNSAFE = Regex("[^\\p{L}\\p{N}._-]+")
    }
}

/** A link of an item to a file it holds: `![name](files/stored)` for a photo, `[name](files/stored)` for a PDF. */
private class Link(match: MatchResult) {
    val photo: Boolean = match.groupValues[1] == "!"
    val name: String = match.groupValues[2]
    val path: String = match.groupValues[3]
}

private val LINK = Regex("""(!?)\[([^\]]*)]\((files/[^)\s]+)\)""")

private fun links(item: String): List<Link> = LINK.findAll(item).map(::Link).toList()

/** An item as the person reads it, in a list: a link to a file becomes `Photo: receipt.jpg` or `PDF: contract.pdf`. */
fun shown(item: String): String = LINK.replace(item) { match -> Link(match).let { "${if (it.photo) "Photo" else "PDF"}: ${it.name}" } }

/** The kinds of files that Peresós eats, besides what is typed. */
internal enum class Food {
    PHOTO,
    PDF,
    TEXT,
    ;

    companion object {
        /** The most of a text file that goes on the pile: a long one is cut. */
        const val MAX_TEXT = 20_000

        private val PHOTOS = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp")
        private val TEXTS = setOf("txt", "text", "md", "markdown", "csv", "log")

        /** What the file at [path] is, from its extension, or `null` when Peresós does not eat it. */
        fun of(path: Path): Food? {
            val extension = path.extension.lowercase()
            return when {
                extension in PHOTOS -> PHOTO
                extension == "pdf" -> PDF
                extension in TEXTS -> TEXT
                else -> null
            }
        }

        fun mediaType(path: Path): String = PHOTOS.getValue(path.extension.lowercase())
    }
}
