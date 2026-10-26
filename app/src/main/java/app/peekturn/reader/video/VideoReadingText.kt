package app.peekturn.reader.video

/** Display-only whitespace reflow. Every displayed UTF-16 character retains its source offset. */
internal class VideoReadingText private constructor(
    val text: String,
    private val sourceOffsets: IntArray,
    private val sourceLength: Int
) {
    fun toSource(selection: CaptionSelection): CaptionSelection? {
        if (selection.start < 0 || selection.end > text.length || selection.start >= selection.end) return null
        return CaptionSelection(sourceOffsets[selection.start], sourceOffsets[selection.end - 1] + 1,
            text.substring(selection.start, selection.end))
    }

    fun toDisplay(selection: CaptionSelection?): CaptionSelection? {
        if (selection == null || selection.start < 0 || selection.end > sourceLength || selection.start >= selection.end) return null
        fun boundary(offset: Int): Int = sourceOffsets.binarySearch(offset).let { if (it >= 0) it else -it - 1 }
        val start = boundary(selection.start)
        val end = boundary(selection.end)
        return if (start < end) CaptionSelection(start, end, text.substring(start, end)) else null
    }

    companion object {
        fun from(source: String): VideoReadingText {
            val text = StringBuilder(source.length)
            val offsets = IntArray(source.length)
            var pendingSpace = -1
            source.forEachIndexed { index, character ->
                if (character.isWhitespace() || Character.isSpaceChar(character)) {
                    if (text.isNotEmpty() && pendingSpace < 0) pendingSpace = index
                } else {
                    if (pendingSpace >= 0) {
                        offsets[text.length] = pendingSpace
                        text.append(' ')
                        pendingSpace = -1
                    }
                    offsets[text.length] = index
                    text.append(character)
                }
            }
            return VideoReadingText(text.toString(), offsets.copyOf(text.length), source.length)
        }
    }
}
