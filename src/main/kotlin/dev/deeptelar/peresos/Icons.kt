package dev.deeptelar.peresos

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * Draws the icon of the app from the face in the tray, for the packages: a PNG for Linux, an ICO
 * for Windows and an ICNS for macOS, in the folder it is given. `./gradlew icons` runs it.
 */
object Icons {
    @JvmStatic
    fun main(args: Array<String>) {
        val folder = Path.of(args.single()).createDirectories()
        val png = png(16)
        folder.resolve("peresos.png").writeBytes(png)
        folder.resolve("peresos.ico").writeBytes(ico(png))
        folder.resolve("peresos.icns").writeBytes(icns(mapOf("ic07" to png(8), "ic08" to png, "ic09" to png(32))))
    }

    /** The face, each of its 16 pixels [scale] pixels wide: 16 makes 256 by 256. */
    private fun png(scale: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(faceImage(scale), "png", it) }.toByteArray()

    /** An ICO with one 256 pixel image, which Windows takes as a PNG. */
    private fun ico(png: ByteArray): ByteArray =
        ByteBuffer
            .allocate(22 + png.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .putShort(1) // an icon
            .putShort(1) // one image
            .put(0)
            .put(0) // 0 means 256 wide and high
            .put(0)
            .put(0)
            .putShort(1)
            .putShort(32)
            .putInt(png.size)
            .putInt(22)
            .put(png)
            .array()

    /** An ICNS of PNGs, by their type: `ic07` is 128 pixels, `ic08` 256 and `ic09` 512. */
    private fun icns(images: Map<String, ByteArray>): ByteArray {
        val size = 8 + images.values.sumOf { 8 + it.size }
        val buffer = ByteBuffer.allocate(size).put("icns".toByteArray()).putInt(size)
        images.forEach { (type, png) -> buffer.put(type.toByteArray()).putInt(8 + png.size).put(png) }
        return buffer.array()
    }
}
