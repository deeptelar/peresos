package dev.deeptelar.peresos

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphResult
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.test.ScriptedChatModel
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PileTest {
    private val home = Files.createTempDirectory("peresos-pile")
    private val pile = FilePile(home.resolve("pile.md"), home.resolve("done"))
    private val elsewhere = Files.createTempDirectory("peresos-downloads")

    private fun photo(name: String = "receipt.jpg"): Path =
        elsewhere.resolve(name).also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it.toFile()) }

    /** A PDF with [text] on its page, or a blank page, as a scan with no text is to PDFBox. */
    private fun pdf(
        name: String,
        text: String? = null,
    ): Path =
        elsewhere.resolve(name).also { path ->
            PDDocument().use { document ->
                val page = PDPage().also(document::addPage)
                if (text != null) {
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                        stream.newLineAtOffset(50f, 700f)
                        stream.showText(text)
                        stream.endText()
                    }
                }
                document.save(path.toFile())
            }
        }

    @Test
    fun `a photo is copied into the home, and its item links to the copy`() {
        val source = photo()
        val item = pile.addFile(source)

        assertTrue(item.startsWith("![receipt.jpg](files/"), item)
        assertEquals(listOf(item), pile.items())
        val copy = home.resolve(item.substringAfter("(").removeSuffix(")"))
        assertTrue(copy.readBytes().contentEquals(source.readBytes()))
        assertEquals("Photo: receipt.jpg", shown(item))
    }

    @Test
    fun `Gemma gets the photo attached, and the item says so`() {
        val source = photo()
        val plate = pile.serve(pile.addFile(source))

        assertEquals("the photo receipt.jpg, attached", plate.text)
        val attached = assertIs<Attachment.Data>(plate.attachments.single())
        assertEquals("image/jpeg", attached.mediaType)
        assertTrue(attached.bytes().contentEquals(source.readBytes()))
    }

    @Test
    fun `a PDF with text gives its text, read on this computer`() {
        val plate = pile.serve(pile.addFile(pdf("invoice.pdf", "Invoice 4711 for the boiler check, due on the third of November")))

        assertContains(plate.text, "the PDF invoice.pdf, which says:")
        assertContains(plate.text, "Invoice 4711 for the boiler check")
        assertTrue(plate.attachments.isEmpty())
        assertEquals("PDF: invoice.pdf", shown(pile.items().single()))
    }

    @Test
    fun `a scan has no text, so Gemma looks at a picture of its page`() {
        val plate = pile.serve(pile.addFile(pdf("scan.pdf")))

        assertContains(plate.text, "its page is attached as a picture")
        assertEquals("image/png", plate.attachments.single().mediaType)
    }

    @Test
    fun `a text file is one item, whatever lines it has, and Gemma gets its text`() {
        val notes = elsewhere.resolve("notes.md").apply { writeText("---\ntitle: Trip\n---\n# Trip\n\nBook the train.\n\n---\n\nAsk Marta about the dog.\n") }
        val item = pile.addFile(notes)

        assertEquals(listOf(item), pile.items())
        assertEquals("Text: notes.md", shown(item))
        val plate = pile.serve(item)
        assertContains(plate.text, "the text file notes.md, which says:")
        assertContains(plate.text, "Ask Marta about the dog.")
        assertTrue(plate.attachments.isEmpty())
        assertTrue(pile.read(item).startsWith("Text: notes.md\n\n---\ntitle: Trip"), "without Gemma, the person reads the file whole")
    }

    @Test
    fun `other files, empty ones, and the pile itself are refused`() {
        val refused = assertFailsWith<IllegalArgumentException> { pile.addFile(elsewhere.resolve("song.mp3").apply { writeText("") }) }
        assertContains(refused.message!!, "song.mp3")
        assertFailsWith<IllegalArgumentException> { pile.addFile(elsewhere.resolve("empty.txt").apply { writeText("  \n") }) }
        pile.add("Reply to the landlord")
        val itself = assertFailsWith<IllegalArgumentException> { pile.addFile(home.resolve("pile.md")) }
        assertContains(itself.message!!, "the pile itself")
        assertEquals(listOf("Reply to the landlord"), pile.items())
    }

    @Test
    fun `a line of dashes in a typed item does not cut it in two`() {
        pile.add("Pack for the trip:\n---\nsocks")
        pile.add("Reply to the landlord")

        assertEquals(listOf("Pack for the trip:\n***\nsocks", "Reply to the landlord"), pile.items())
    }

    @Test
    fun `an edited item stays where it was, and keeps its files`() {
        pile.add("Reply to the landlord")
        val bill = pile.addFile(photo("bill.jpg"))
        pile.add("Decide on the gym")

        pile.replace("Reply to the landlord", "Reply to the landlord: Tuesday works")
        pile.replace(bill, rewrite(bill, "Pay it before Friday"))

        val items = pile.items()
        assertEquals("Reply to the landlord: Tuesday works", items[0])
        assertEquals("Pay it before Friday", words(items[1]))
        assertEquals(listOf("Photo: bill.jpg"), links(items[1]).map { it.label })
        assertTrue(pile.path(links(items[1]).single()).exists(), "the photo stays")
        assertEquals("Decide on the gym", items[2])
    }

    @Test
    fun `of two items with the same text, the one at its place is edited`() {
        pile.add("Decide on the gym")
        pile.add("Reply to Marta")
        pile.add("Decide on the gym")

        pile.replace("Decide on the gym", "Cancel the gym", at = 2)
        pile.replace("Reply to Marta", "", at = 1)

        assertEquals(listOf("Decide on the gym", "Cancel the gym"), pile.items())
    }

    @Test
    fun `two writers at once lose nothing, and a reader never sees half a pile`() {
        val writers = 4
        val each = 60
        val torn = java.util.concurrent.atomic.AtomicInteger()
        val reading = java.util.concurrent.atomic.AtomicBoolean(true)
        val reader =
            Thread {
                while (reading.get()) {
                    val items = pile.items()
                    if (items.any { !it.startsWith("Writer ") || !it.endsWith(" done") }) torn.incrementAndGet()
                }
            }.apply { start() }
        // Each writer has its own pile on the same file, as a terminal and the window do.
        (1..writers).map { writer ->
            Thread {
                val own = FilePile(home.resolve("pile.md"), home.resolve("done"))
                repeat(each) { own.add("Writer $writer, item $it, done") }
            }.apply { start() }
        }.forEach { it.join() }
        reading.set(false)
        reader.join()

        assertEquals(writers * each, pile.items().size, "every item is there")
        assertEquals(0, torn.get(), "the reader saw a pile cut halfway")
    }

    @Test
    fun `a file whose link is edited away leaves with it`() {
        val bill = pile.addFile(photo("bill.jpg"))
        val copy = pile.path(links(bill).single())

        pile.replace(bill, "Pay the bill")

        assertEquals(listOf("Pay the bill"), pile.items())
        assertFalse(copy.exists())
    }

    @Test
    fun `the copy goes when its item leaves the pile, and next to the finished version when taken`() {
        val binned = pile.addFile(photo("old.jpg"))
        val taken = pile.addFile(photo("receipt.jpg"))
        val files = home.resolve("files")
        assertEquals(2, files.listDirectoryEntries().size)

        pile.remove(binned)
        assertEquals(1, files.listDirectoryEntries().size)

        pile.keep("2026-10-24", taken, Bite(Kind.TASK, "File the receipt", "Put it in the folder of expenses."))
        pile.remove(taken)
        assertTrue(files.listDirectoryEntries().isEmpty())
        val done = home.resolve("done/2026-10-24-file-the-receipt.md").readText()
        assertContains(done, "From ![receipt.jpg](2026-10-24-file-the-receipt-receipt.jpg)")
        assertTrue(home.resolve("done/2026-10-24-file-the-receipt-receipt.jpg").exists())
    }

    @Test
    fun `a file that was deleted by hand is said to be gone`() {
        val item = pile.addFile(photo())
        home.resolve("files").listDirectoryEntries().forEach(Files::delete)
        val plate = pile.serve(item)
        assertEquals("the file receipt.jpg, which is no longer there", plate.text)
        assertTrue(plate.attachments.isEmpty())
    }

    @Test
    fun `a wake on a photo sends it to Gemma with the item`() =
        runTest {
            pile.addFile(photo())
            val model = ScriptedChatModel { answers(Bite(Kind.TASK, "File the receipt", "Put it in the folder of expenses.")) }
            val config = GraphConfig(threadId = "test", checkpointer = MemoryCheckpointer<Sloth>(), interruptBefore = setOf(SWALLOW))

            val result = sloth(model, pile).invoke(Sloth("2026-10-24"), config)

            assertIs<GraphResult.Interrupted<Sloth>>(result)
            val asked = model.requests.single().messages.last() as ChatMessage.User
            assertContains(asked.text, "the photo receipt.jpg, attached")
            assertEquals("image/jpeg", asked.attachments.single().mediaType)
            assertFalse("base64" in result.state.toString(), "the photo is never in the checkpoint")
        }
}
