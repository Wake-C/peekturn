package app.peekturn.reader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** A slightly stronger neutral fill, shared by video transcript rows and flowing Read text. */
internal val ColorScheme.activeSentenceBackground: Color
    get() = surfaceContainerHighest
