package app.peekturn.reader.data

/** A navigation preference, never updated by background playback or position writes. */
sealed interface LastContent {
    val id: Long
    data class Book(override val id: Long) : LastContent
    data class Video(override val id: Long) : LastContent
}
