package dev.deeptelar.peresos

import dev.deeptelar.telar.agent.Attachment
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Puts [with] where [item] is on the pile, as the person edited it: at [at] when the item is
     * there, as the same text can be on the pile twice, else where it first is.
     */
    fun replace(
        item: String,
        with: String,
        at: Int = -1,
    )

    fun moveToEnd(item: String)

    /** Keeps the finished version of an [item] that the person took. */
    fun keep(day: String, item: String, bite: Bite)

    /** The [item] as the person reads it whole, as it comes without Gemma: with the text of its text files. */
    fun read(item: String): String = shown(item)

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
 * A file fed to it is copied into `files`, next to [file], and its item is a link to the copy, in
 * Markdown: `![receipt.jpg](files/20261024-153000-receipt.jpg)` for a photo, `[notes.md](files/...)`
 * for a PDF or a text file, so a whole document is one item. The copy goes when its item leaves the
 * pile, into [done] if the person took it.
 */
class FilePile(val file: Path, val done: Path) : Pile {
    private val folder: Path = file.toAbsolutePath().parent

    override fun items(): List<String> =
        if (file.exists()) file.readText().split(BETWEEN).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

    override fun add(item: String) = changing { write(items() + kept(item)) }

    /**
     * Puts the file at [source] on the pile, and returns its item: the file is copied in, so the
     * original can move, and the item links to the copy.
     *
     * @throws IllegalArgumentException when Peresós does not eat files of its type.
     */
    fun addFile(source: Path): String {
        val name = source.name
        val food = Food.of(source) ?: throw IllegalArgumentException("Peresós eats photos (JPEG, PNG, GIF, WebP), PDFs and text files, and $name is none of them.")
        if (file.exists() && Files.isSameFile(source, file)) throw IllegalArgumentException("$name is the pile itself.")
        if (food == Food.TEXT && source.readText().isBlank()) throw IllegalArgumentException("$name is empty.")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return changing {
            val files = folder.resolve(FILES).createDirectories()
            val stored = generateSequence(0) { it + 1 }.map { n -> "$stamp${if (n == 0) "" else "-$n"}-${name.replace(UNSAFE, "-")}" }.first { !files.resolve(it).exists() }
            source.copyTo(files.resolve(stored))
            val item = "${if (food == Food.PHOTO) "!" else ""}[${name.replace("]", "")}]($FILES/$stored)"
            write(items() + item)
            item
        }
    }

    override fun remove(item: String) =
        changing {
            val items = items()
            val at = items.indexOf(item.trim())
            if (at >= 0) {
                write(items.filterIndexed { index, _ -> index != at })
                forget(item)
            }
        }

    override fun replace(
        item: String,
        with: String,
        at: Int,
    ) = changing {
        val items = items()
        val where = at.takeIf { items.getOrNull(it) == item.trim() } ?: items.indexOf(item.trim())
        when {
            where < 0 -> Unit
            with.isBlank() -> {
                write(items.filterIndexed { index, _ -> index != where })
                forget(item)
            }
            else -> {
                write(items.mapIndexed { index, it -> if (index == where) kept(with) else it })
                forget(item)
            }
        }
    }

    /**
     * Changes the pile while it holds `pile.lock` next to it, so that a terminal and the window never
     * write it at the same time, and neither loses what the other wrote.
     */
    private fun <T> changing(change: () -> T): T =
        synchronized(GUARDS.computeIfAbsent(file.toAbsolutePath().normalize()) { Any() }) {
            folder.createDirectories()
            FileChannel.open(folder.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { change() }
            }
        }

    /** Deletes the copies of the files of [item] that left the pile, unless another item holds them too. */
    private fun forget(item: String) {
        val left = items().flatMap { links(it) }.map { it.path }.toSet()
        links(item).filter { it.path !in left }.forEach { folder.resolve(it.path).deleteIfExists() }
    }

    /** The full path of the copy that [link] points to, to open it. */
    fun path(link: Attached): Path = folder.resolve(link.path)

    override fun moveToEnd(item: String) =
        changing {
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

    override fun read(item: String): String =
        LINK.replace(item) { match ->
            val link = Attached(match)
            val path = folder.resolve(link.path)
            if (link.kind == Food.TEXT && path.exists()) "${link.label}\n\n${path.readText().trim().take(Food.MAX_TEXT)}\n" else link.label
        }.trim()

    /**
     * The item with its photos attached, and with the text of its PDFs; a PDF with no text, a scan,
     * is attached as pictures of its first pages.
     */
    override fun serve(item: String): Plate {
        val attachments = mutableListOf<Attachment>()
        val text =
            LINK.replace(item) { match ->
                val link = Attached(match)
                val path = folder.resolve(link.path)
                when {
                    !path.exists() -> "the file ${link.name}, which is no longer there"
                    link.kind == Food.TEXT -> "the text file ${link.name}, which says:\n\"\"\"\n${path.readText().trim().take(Food.MAX_TEXT)}\n\"\"\"\n"
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

    /** Writes the pile whole, into a file next to it that then takes its place: a reader never sees half of it. */
    private fun write(items: List<String>) {
        file.parent?.let { Files.createDirectories(it) }
        val next = file.resolveSibling("${file.name}.next")
        next.writeText(items.joinToString(SEPARATOR, postfix = if (items.isEmpty()) "" else "\n"))
        try {
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Windows refuses while another program reads the pile: then it writes it in place.
            file.writeText(next.readText())
            next.deleteIfExists()
        }
    }

    private companion object {
        const val SEPARATOR = "\n---\n"
        const val FILES = "files"
        const val LOCK = "pile.lock"

        /** One per pile in this process, which the lock of the file does not keep out. */
        val GUARDS = ConcurrentHashMap<Path, Any>()

        /** A line with `---` alone, also one the person typed with spaces around it. */
        val BETWEEN = Regex("(?m)^\\s*---\\s*$")

        /** The same line inside one item, which would cut it in two. */
        val CUT = Regex("(?m)^[^\\S\\n]*---[^\\S\\n]*$")

        /** [item] as it goes in the file: a line of `---` in it becomes `***`, a line in Markdown too. */
        fun kept(item: String): String = item.trim().replace(CUT, "***")

        /** What a name of a file may not keep, to stay a plain link in Markdown. */
        val UNSAFE = Regex("[^\\p{L}\\p{N}._-]+")
    }
}

/** A link of an item to a file it holds: `![name](files/stored)` for a photo, `[name](files/stored)` for a PDF or a text file. */
class Attached internal constructor(match: MatchResult) {
    val photo: Boolean = match.groupValues[1] == "!"
    val name: String = match.groupValues[2]
    internal val path: String = match.groupValues[3]
    internal val kind: Food? = Food.of(Path.of(path))

    /** What it is and its name, as `Photo: receipt.jpg`. */
    val label: String get() = "${if (photo) "Photo" else if (kind == Food.TEXT) "Text" else "PDF"}: $name"

    /** The link as it is written in the item. */
    internal val link: String = match.value
}

private val LINK = Regex("""(!?)\[([^\]]*)]\((files/[^)\s]+)\)""")

/** The files that [item] holds. */
fun links(item: String): List<Attached> = LINK.findAll(item).map(::Attached).toList()

/** What the person wrote in [item], without the links to its files. */
fun words(item: String): String = LINK.replace(item, "").trim()

/** [item] with [words] in place of what the person wrote in it: its files stay, first. */
fun rewrite(
    item: String,
    words: String,
): String = (links(item).map { it.link } + words.trim()).filter { it.isNotEmpty() }.joinToString("\n")

/** An item in one line, for a list: its lines joined, with its files as in [shown]. */
fun line(item: String): String = shown(item).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/** An item as the person reads it, in a list: a link to a file becomes `Photo: receipt.jpg`, `PDF: contract.pdf` or `Text: notes.md`. */
fun shown(item: String): String = LINK.replace(item) { Attached(it).label }

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
