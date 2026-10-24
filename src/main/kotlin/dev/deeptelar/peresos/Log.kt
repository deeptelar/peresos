package dev.deeptelar.peresos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.readLines

/** The version of Peresós, from its jar, or `dev` when it runs from Gradle. */
val VERSION: String = Home::class.java.`package`?.implementationVersion ?: "dev"

/**
 * What went wrong, with its stack trace, in `logs/peresos.log` of the home. The window has no
 * terminal to print to, so this is where its failures go; `peresos doctor --report` puts the end
 * of it in a file for an issue. It stays on this computer.
 */
class Log(private val folder: Path) {
    val file: Path = folder.resolve("peresos.log")

    /** Writes [cause] under [what]. Never throws: a log that cannot be written must not hide the failure itself. */
    fun error(
        what: String,
        cause: Throwable,
    ) {
        try {
            folder.createDirectories()
            if (file.exists() && file.fileSize() > MAX_SIZE) file.moveTo(folder.resolve("peresos.log.1"), overwrite = true)
            val entry = "${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} peresos $VERSION: $what\n${cause.stackTraceToString()}\n"
            Files.writeString(file, entry, CREATE, APPEND)
        } catch (_: IOException) {
            // Nowhere to write it: the person still sees the message.
        }
    }

    /**
     * The last [count] failures of the log, newest last, each cut to its first [lines] lines: the
     * header and the top of the stack trace, which say what failed and where.
     */
    fun last(
        count: Int,
        lines: Int = Int.MAX_VALUE,
    ): List<String> {
        if (!file.exists()) return emptyList()
        val entries = mutableListOf<MutableList<String>>()
        file.readLines().forEach { line -> if (ENTRY.containsMatchIn(line) || entries.isEmpty()) entries += mutableListOf(line) else entries.last() += line }
        return entries.takeLast(count).flatMap { it.take(lines) }
    }

    private companion object {
        const val MAX_SIZE = 1_000_000L
        val ENTRY = Regex("""^\d{4}-\d{2}-\d{2}T""")
    }
}
