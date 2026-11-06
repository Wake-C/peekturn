package app.peekturn.reader.learning

import app.peekturn.reader.model.LookupContext
import com.google.mlkit.vision.text.Text
import kotlin.math.max

/** Pixel coordinates in the captured full-display image, not in the overlay's local window. */
internal data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun distanceSquared(x: Float, y: Float): Float {
        val dx = maxOf(left - x, 0f, x - right)
        val dy = maxOf(top - y, 0f, y - bottom)
        return dx * dx + dy * dy
    }
}
internal data class ScreenWord(val text: String, val box: ScreenBox, val start: Int, val end: Int)
internal data class ScreenLine(val text: String, val box: ScreenBox, val words: List<ScreenWord>, val block: Int)

/** ML Kit elements are usually words, but split an element if the engine groups several words. */
internal fun screenLines(result: Text): List<ScreenLine> = buildList {
    val englishWord = Regex("[\\p{L}]+(?:['’-][\\p{L}]+)*")
    result.textBlocks.forEachIndexed { block, textBlock ->
        textBlock.lines.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            val text = line.elements.joinToString(" ") { it.text }
            var offset = 0
            val words = buildList {
                line.elements.forEach { element ->
                    val elementBox = element.boundingBox
                    if (elementBox != null && element.text.isNotEmpty()) {
                        englishWord.findAll(element.text).forEach { match ->
                            val width = elementBox.width().toFloat() / element.text.length
                            add(ScreenWord(match.value,
                                ScreenBox(elementBox.left + (width * match.range.first).toInt(), elementBox.top,
                                    elementBox.left + (width * (match.range.last + 1)).toInt(), elementBox.bottom),
                                offset + match.range.first, offset + match.range.last + 1))
                        }
                    }
                    offset += element.text.length + 1
                }
            }
            if (words.isNotEmpty()) add(ScreenLine(text,
                ScreenBox(box.left, box.top, box.right, box.bottom), words, block))
        }
    }
}

/** A near miss is allowed for a fingertip, but never substitute a distant occurrence of the word. */
internal fun screenLookup(lines: List<ScreenLine>, x: Float, y: Float, tolerance: Float): LookupContext? {
    val candidates = lines.flatMap { line -> line.words.map { line to it } }
    val (line, word) = candidates.minByOrNull { (_, token) -> token.box.distanceSquared(x, y) } ?: return null
    if (word.box.distanceSquared(x, y) > tolerance * tolerance) return null
    val index = lines.indexOf(line)
    val previous = lines.getOrNull(index - 1)?.takeIf { it.block == line.block && aligned(it.box, line.box) }
    val next = lines.getOrNull(index + 1)?.takeIf { it.block == line.block && aligned(it.box, line.box) }
    val before = listOfNotNull(previous?.text, line.text.substring(0, word.start).trim())
        .filter { it.isNotBlank() }.joinToString(" ")
    val after = listOfNotNull(line.text.substring(word.end).trim(), next?.text)
        .filter { it.isNotBlank() }.joinToString(" ")
    return LookupContext(selection = word.text, before = before, after = after)
}

private fun aligned(a: ScreenBox, b: ScreenBox): Boolean {
    val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
    return overlap > 0 && overlap * 5 >= minOf(a.right - a.left, b.right - b.left)
}
