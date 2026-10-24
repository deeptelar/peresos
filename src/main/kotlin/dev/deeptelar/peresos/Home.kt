package dev.deeptelar.peresos

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.checkpoint.file.FileCheckpointer
import dev.deeptelar.telar.openai.OpenAiChatModel
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlinx.io.files.Path as IoPath

/**
 * Where Peresós lives on disk: its pile, its checkpoints, what you told it about yourself and what
 * it wears. The terminal and the window share one home, so both show the same sloth.
 */
class Home(val path: Path) {
    /** The folder as the person would type it, with `~` for their home. */
    val shown: String = path.toString().replaceFirst(System.getProperty("user.home"), "~")

    val pile: FilePile = FilePile(path.resolve("pile.md"), path.resolve("done"))

    /** What went wrong, for `peresos doctor --report`. */
    val log: Log = Log(path.resolve("logs"))

    /** The one thread of the sloth, saved after every step, and paused before it swallows. */
    val config: GraphConfig<Sloth> =
        GraphConfig(
            threadId = "peresos",
            checkpointer = FileCheckpointer(IoPath(path.toString(), "checkpoints"), KotlinxStateSerializer<Sloth>()),
            interruptBefore = setOf(SWALLOW),
        )

    private val aboutFile = path.resolve("about.md")
    private val wearFile = path.resolve("wear")
    private val awakeFile = path.resolve("awake.lock")
    private val placeFile = path.resolve("place")
    private val windowFile = path.resolve("window.lock")
    private val callFile = path.resolve("window.call")

    /** What you told it about yourself, one fact per line. */
    fun about(): String = aboutFile.readTextOrEmpty()

    fun learn(fact: String) {
        if (fact.isBlank()) return
        path.createDirectories()
        aboutFile.writeText(about() + "- ${fact.trim()}\n")
    }

    /** What it has on, one item per line of the wear file. */
    fun wearing(): List<Item> = wearFile.readTextOrEmpty().lines().mapNotNull { line -> Item.entries.find { it.name.equals(line.trim(), ignoreCase = true) } }

    /** Puts [items] on over what it wears. One item in each slot: what it puts on last replaces what it had there. */
    fun putOn(items: Collection<Item>) = dress((wearing() + items).associateBy { it.slot }.values)

    fun takeOff(item: Item) = dress(wearing() - item)

    /** Wears exactly [outfit], nothing else. */
    fun dress(outfit: Collection<Item>) {
        path.createDirectories()
        wearFile.writeText(outfit.sortedBy { it.ordinal }.joinToString("") { it.name.lowercase() + "\n" })
    }

    /** The town of the window's weather, or `null`: then the window never asks for the weather. */
    fun place(): Place? {
        val (name, latitude, longitude) = placeFile.readTextOrEmpty().lines().takeIf { it.size >= 3 } ?: return null
        return Place(name, latitude.toDoubleOrNull() ?: return null, longitude.toDoubleOrNull() ?: return null)
    }

    /** Sets the town of the weather, or forgets it with `null`. */
    fun settle(place: Place?) {
        path.createDirectories()
        placeFile.writeText(place?.let { "${it.name}\n${it.latitude}\n${it.longitude}\n" } ?: "")
    }

    /** Changes when any file of the home changes, so a window can tell that the terminal fed, dressed or woke it. */
    fun version(): List<Long> = listOf(pile.file, aboutFile, wearFile, placeFile, path.resolve("checkpoints/peresos.json")).map { it.toFile().lastModified() }

    /** It ate the whole pile, so it sleeps on top of its branch until there is something new. */
    fun isFull(sloth: Sloth?): Boolean = sloth != null && sloth.roundness > 0 && pile.items().isEmpty()

    /**
     * Wakes it, unless it is already awake in another terminal or window: two wakes at once would
     * both write its checkpoint. Close what this returns when the wake is over; `null` means it is busy.
     */
    fun awake(): AutoCloseable? = lock(awakeFile)

    /** Holds while its window runs, so a second `peresos window` shows that one instead. `null` means one runs already. */
    fun window(): AutoCloseable? = lock(windowFile)

    /** Asks the window that runs to show itself. */
    fun callWindow() {
        path.createDirectories()
        callFile.writeText("${System.currentTimeMillis()}\n")
    }

    /** Changes every time something asked the window to show itself. */
    fun windowCalls(): Long = callFile.toFile().lastModified()

    private fun lock(file: Path): AutoCloseable? {
        path.createDirectories()
        val channel = FileChannel.open(file, CREATE, WRITE)
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null // this process holds it already
            }
        if (lock == null) {
            channel.close()
            return null
        }
        return AutoCloseable { channel.close() }
    }

    companion object {
        /** `~/.peresos`, or the folder in `PERESOS_HOME`. */
        val default: Home = Home(Path.of(System.getenv("PERESOS_HOME") ?: "${System.getProperty("user.home")}/.peresos"))

        val modelName: String = System.getenv("PERESOS_MODEL") ?: "gemma4:12b"

        /** Where Ollama listens: `OLLAMA_HOST`, read the way Ollama reads it, or its default. */
        val ollama: String = ollamaUrl(System.getenv("OLLAMA_HOST"))

        /** Gemma in the local Ollama. It thinks before it answers: worth it for the finished version, not for a line of the diary. */
        fun model(
            client: HttpClient,
            think: Boolean = true,
        ): OpenAiChatModel =
            OpenAiChatModel.ollama(
                client,
                model = modelName,
                baseUrl = "$ollama/v1",
                parameters = if (think) JsonObject(emptyMap()) else buildJsonObject { put("reasoning_effort", "none") },
                timeout = 15.minutes,
            )
    }
}

/**
 * The address of Ollama for the value of `OLLAMA_HOST`: `127.0.0.1:11434` when it is empty, as
 * Ollama does. `0.0.0.0`, which tells Ollama to listen everywhere, is reached at `127.0.0.1`.
 */
internal fun ollamaUrl(host: String?): String {
    val value = host?.trim()?.trimEnd('/').orEmpty()
    if (value.isEmpty()) return "http://127.0.0.1:11434"
    val uri = URI(if ("://" in value) value else "http://$value")
    val port = if (uri.port != -1) uri.port else if ("://" !in value) 11434 else if (uri.scheme == "https") 443 else 80
    val name = uri.host?.takeUnless { it == "0.0.0.0" || it == "[::]" } ?: "127.0.0.1"
    return "${uri.scheme}://$name:$port"
}

internal fun Path.readTextOrEmpty(): String = if (exists()) readText() else ""
