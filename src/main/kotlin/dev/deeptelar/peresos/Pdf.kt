package dev.deeptelar.peresos

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * What Peresós reads of a PDF, on this computer: Ollama takes no PDFs, so Gemma gets its text, or,
 * when it has none because it is a scan, pictures of its first pages.
 */
internal sealed interface Pdf {
    /** Its text, cut at [MAX_TEXT] characters. */
    class Text(val text: String) : Pdf

    /** Its first pages, as PNG pictures. */
    class Pages(val pages: List<ByteArray>) : Pdf

    /** It has a password. */
    data object Locked : Pdf

    companion object {
        const val MAX_TEXT = 30_000

        /** The pages of a scan that go to Gemma: each is a picture, and pictures are slow for it. */
        const val MAX_PAGES = 3

        /** Fewer letters than this, and the PDF is taken for a scan. */
        const val SOME_TEXT = 40
    }
}

/** Reads the PDF at [path]: its text, or pictures of its first pages when it has no text. */
internal fun readPdf(path: Path): Pdf =
    try {
        Loader.loadPDF(path.toFile()).use { document ->
            val text = PDFTextStripper().getText(document).trim()
            if (text.count { it.isLetterOrDigit() } >= Pdf.SOME_TEXT) {
                Pdf.Text(text.take(Pdf.MAX_TEXT))
            } else {
                val renderer = PDFRenderer(document)
                Pdf.Pages(
                    (0 until min(document.numberOfPages, Pdf.MAX_PAGES)).map { page ->
                        ByteArrayOutputStream().also { ImageIO.write(renderer.renderImageWithDPI(page, 110f), "png", it) }.toByteArray()
                    },
                )
            }
        }
    } catch (_: InvalidPasswordException) {
        Pdf.Locked
    }
