package dev.deeptelar.peresos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A small kit of pixel-art controls for the window: everything is drawn in square pixels of PX,
// with hard outlines and hard shadows, like the sloth.

/** One pixel of the controls' drawings. */
internal val PX: Dp = 3.dp

/** Pixelify Sans, a pixel font under the SIL Open Font License (resources/fonts/OFL.txt). Only its regular weight sits on its grid. */
internal val PixelFont: FontFamily by lazy {
    val data = checkNotNull(Portrait::class.java.getResourceAsStream("/fonts/PixelifySans.ttf")).use { it.readBytes() }
    FontFamily(Font("PixelifySans", data, FontWeight.Normal, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(400))))
}

// A pixel of Pixelify Sans is 0.091 of its size: at these sizes its pixels fall on whole screen pixels.
internal val SMALL: TextUnit = 16.5.sp // a pixel and a half: slightly soft, for notes
internal val BODY: TextUnit = 22.sp
internal val BIG: TextUnit = 33.sp

/** No ligatures: in a pixel font, "fi" drawn as one letter reads as an A. */
private const val PLAIN = "liga 0, clig 0, calt 0"

/** The colors of the window: bark, parchment and ink, and a color for each answer. */
internal object Ink {
    val text = Color(0xFF2B1D14)
    val faint = Color(0xFF7A6248)
    val paper = Color(0xFFF6E7C4)
    val paperDark = Color(0xFFDDC495)
    val field = Color(0xFFFFF7E2)
    val wood = Color(0xFF6B4226)
    val woodDark = Color(0xFF4A2C17)
    val woodLight = Color(0xFF8A5A34)
    val green = Color(0xFF5DAE3B)
    val red = Color(0xFFD9534F)
    val yellow = Color(0xFFF2B83A)
    val blue = Color(0xFF4A90D9)
    val off = Color(0xFFB3A48C)
}

/** Text in the pixel font. */
@Composable
internal fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = BODY,
    color: Color = Ink.text,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    text,
    modifier,
    style = TextStyle(fontFamily = PixelFont, fontSize = size, color = color, lineHeight = size * 1.25f, fontFeatureSettings = PLAIN),
    maxLines = maxLines,
)

/**
 * A pixel box: [fill] inside an outline with its corners cut off, a lighter line along the top
 * and a darker one along the bottom, and a hard shadow under it unless [raised] is false.
 */
internal fun Modifier.pixelBox(
    fill: Color,
    outline: Color = Ink.text,
    raised: Boolean = true,
): Modifier =
    drawBehind {
        val p = PX.toPx()
        val w = size.width
        val h = size.height
        if (raised) notched(Color(0x55000000), Offset(p, p), w, h, p)
        notched(outline, Offset.Zero, w, h, p)
        drawRect(fill, Offset(p, p), Size(w - 2 * p, h - 2 * p))
        drawRect(lighter(fill), Offset(p, p), Size(w - 2 * p, p))
        drawRect(darker(fill), Offset(p, h - 2 * p), Size(w - 2 * p, p))
    }

/** A rectangle with one pixel cut from each corner. */
private fun DrawScope.notched(
    color: Color,
    at: Offset,
    w: Float,
    h: Float,
    p: Float,
) {
    drawRect(color, at + Offset(p, 0f), Size(w - 2 * p, h))
    drawRect(color, at + Offset(0f, p), Size(w, h - 2 * p))
}

private fun lighter(color: Color) = Color(color.red + (1 - color.red) * 0.25f, color.green + (1 - color.green) * 0.25f, color.blue + (1 - color.blue) * 0.25f)

private fun darker(color: Color) = Color(color.red * 0.82f, color.green * 0.82f, color.blue * 0.82f)

/** A chunky button in [color], that goes down when pressed. */
@Composable
internal fun PixelButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val face = if (enabled) color else Ink.off
    val light = (face.red * 0.3f + face.green * 0.59f + face.blue * 0.11f) > 0.62f
    Box(
        Modifier
            .offset(y = if (pressed) PX else 0.dp)
            .pixelBox(face, raised = !pressed)
            .clickable(press, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 7.dp else 10.dp),
    ) {
        PixelText(text, size = if (small) SMALL else BODY, color = if (light || !enabled) Ink.text else Color.White)
    }
}

/** Tabs like the labels of a ledger, spread over the width: the chosen one is parchment, the others bark. */
@Composable
internal fun PixelTabs(
    titles: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        titles.forEachIndexed { index, title ->
            val chosen = index == selected
            Box(
                Modifier
                    .pixelBox(if (chosen) Ink.paper else Ink.woodLight, raised = chosen)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 7.dp, vertical = 7.dp),
            ) {
                PixelText(title, color = if (chosen) Ink.text else Ink.paper, maxLines = 1)
            }
        }
    }
}

/** A text field in a pixel frame, with [placeholder] while it is empty. */
@Composable
internal fun PixelField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value,
        onChange,
        modifier.pixelBox(Ink.field, raised = false),
        textStyle = TextStyle(fontFamily = PixelFont, fontSize = BODY, color = Ink.text, lineHeight = BODY * 1.25f, fontFeatureSettings = PLAIN),
        cursorBrush = SolidColor(Ink.text),
        maxLines = 4,
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.isEmpty()) PixelText(placeholder, color = Ink.faint)
                field()
            }
        },
    )
}

/** A square to tick, with its label. */
@Composable
internal fun PixelCheck(
    checked: Boolean,
    label: String,
    note: String,
    onClick: () -> Unit,
) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(24.dp).pixelBox(if (checked) Ink.green else Ink.field, raised = false), contentAlignment = Alignment.Center) {
            if (checked) PixelText("✓", size = SMALL, color = Color.White)
        }
        PixelText(label, Modifier.width(132.dp), maxLines = 1)
        PixelText(note, size = SMALL, color = Ink.faint)
    }
}

/** A bar of blocks with one bright block running across it, while it waits. */
@Composable
internal fun PixelProgress(tick: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(14) { block ->
            val distance = (tick % 26).let { if (it < 14) it else 26 - it } - block
            val color = if (distance == 0) Ink.green else if (distance in -1..1) Ink.green.copy(alpha = 0.55f) else Ink.paperDark
            Box(Modifier.size(16.dp, 12.dp).drawBehind { drawRect(color) })
        }
    }
}
