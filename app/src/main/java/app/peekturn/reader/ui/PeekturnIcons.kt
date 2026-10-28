package app.peekturn.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Peekturn's shared icon family: Lucide outlines on a 24dp grid, with rounded 1.8dp strokes.
 * Converted from upstream SVGs; only the stroke weight is adapted to our reading chrome.
 * Source: lucide-icons/lucide@c2dd25753da3f25d9ddc351ddb0ee72332189692 (ISC / MIT).
 * Complete upstream license: assets/licenses/LICENSE-Lucide.txt.
 * Keep navigation, reader, video and popup icons here so their geometry stays consistent.
 * Directional navigation mirrors in RTL; media transport keeps its playback direction.
 */
internal object PeekturnIcons {
    val BookOpen: ImageVector by lazy {
        outline("BookOpen",
            "M 12,5 v 16",
            "M 20.001,19 A 2,2 0 0,0 22,17 V 5 a 2,2 0 0,0 -1.999,-2 L 16,3.002 A 5,5 0 0,0 12,5 a 5,5 0 0,0 -4,-2 H 4 a 2,2 0 0,0 -2,2 v 12 a 2,2 0 0,0 1.999,2 H 8 a 5,5 0 0,1 4,2 a 5,5 0 0,1 4,-2 z"
        )
    }

    val Video: ImageVector by lazy {
        outline("Video",
            "m 12.296,3.464 l 3.02,3.956",
            "M 20.2,6 L 3,11 l -0.9,-2.4 c -0.3,-1.1 0.3,-2.2 1.3,-2.5 l 13.5,-4 c 1.1,-0.3 2.2,0.3 2.5,1.3 z",
            "M 3,11 h 18 v 8 a 2,2 0 0,1 -2,2 H 5 a 2,2 0 0,1 -2,-2 z",
            "m 6.18,5.276 l 3.1,3.899"
        )
    }

    val Statistics: ImageVector by lazy {
        outline("Statistics",
            "M 5,21 v -6",
            "M 12,21 V 9",
            "M 19,21 V 3"
        )
    }

    val Settings: ImageVector by lazy {
        outline("Settings",
            "M 14,17 H 5",
            "M 19,7 h -9",
            "M 14,17 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0 Z",
            "M 4,7 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0 Z"
        )
    }

    val Add: ImageVector by lazy {
        outline("Add",
            "M 5,12 h 14",
            "M 12,5 v 14"
        )
    }

    val Search: ImageVector by lazy {
        outline("Search",
            "m 21,21 l -4.34,-4.34",
            "M 3,11 a 8,8 0 1,0 16,0 a 8,8 0 1,0 -16,0 Z"
        )
    }

    val More: ImageVector by lazy {
        outline("More",
            "M 11,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0 Z",
            "M 18,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0 Z",
            "M 4,12 a 1,1 0 1,0 2,0 a 1,1 0 1,0 -2,0 Z"
        )
    }

    val ChevronRight: ImageVector by lazy {
        outline("ChevronRight",
            "m 9,18 l 6,-6 l -6,-6",
            autoMirror = true
        )
    }

    val ChevronUp: ImageVector by lazy {
        outline("ChevronUp",
            "m 18,15 l -6,-6 l -6,6"
        )
    }

    val ChevronDown: ImageVector by lazy {
        outline("ChevronDown",
            "m 6,9 l 6,6 l 6,-6"
        )
    }

    val Appearance: ImageVector by lazy {
        outline("Appearance",
            "M 8,12 a 4,4 0 1,0 8,0 a 4,4 0 1,0 -8,0 Z",
            "M 12,2 v 2",
            "M 12,20 v 2",
            "m 4.93,4.93 l 1.41,1.41",
            "m 17.66,17.66 l 1.41,1.41",
            "M 2,12 h 2",
            "M 20,12 h 2",
            "m 6.34,17.66 l -1.41,1.41",
            "m 19.07,4.93 l -1.41,1.41"
        )
    }

    val Language: ImageVector by lazy {
        outline("Language",
            "m 5,8 l 6,6",
            "m 4,14 l 6,-6 l 2,-3",
            "M 2,5 h 12",
            "M 7,2 h 1",
            "m 22,22 l -5,-10 l -5,10",
            "M 14,18 h 6"
        )
    }

    val Headphones: ImageVector by lazy {
        outline("Headphones",
            "M 3,14 h 3 a 2,2 0 0,1 2,2 v 3 a 2,2 0 0,1 -2,2 H 5 a 2,2 0 0,1 -2,-2 v -7 a 9,9 0 0,1 18,0 v 7 a 2,2 0 0,1 -2,2 h -1 a 2,2 0 0,1 -2,-2 v -3 a 2,2 0 0,1 2,-2 h 3"
        )
    }

    val Storage: ImageVector by lazy {
        outline("Storage",
            "M 10,16 h 0.01",
            "M 2.212,11.577 a 2,2 0 0,0 -0.212,0.896 V 18 a 2,2 0 0,0 2,2 h 16 a 2,2 0 0,0 2,-2 v -5.527 a 2,2 0 0,0 -0.212,-0.896 L 18.55,5.11 A 2,2 0 0,0 16.76,4 H 7.24 a 2,2 0 0,0 -1.79,1.11 z",
            "M 21.946,12.013 H 2.054",
            "M 6,16 h 0.01"
        )
    }

    val Audio: ImageVector by lazy {
        outline("Audio",
            "M 2,10 v 3",
            "M 6,6 v 11",
            "M 10,3 v 18",
            "M 14,8 v 7",
            "M 18,5 v 13",
            "M 22,10 v 3"
        )
    }

    val Diagnostics: ImageVector by lazy {
        outline("Diagnostics",
            "M 12,20 v -9",
            "M 14,7 a 4,4 0 0,1 4,4 v 3 a 6,6 0 0,1 -12,0 v -3 a 4,4 0 0,1 4,-4 z",
            "M 14.12,3.88 L 16,2",
            "M 21,21 a 4,4 0 0,0 -3.81,-4",
            "M 21,5 a 4,4 0 0,1 -3.55,3.97",
            "M 22,13 h -4",
            "M 3,21 a 4,4 0 0,1 3.81,-4",
            "M 3,5 a 4,4 0 0,0 3.55,3.97",
            "M 6,13 H 2",
            "m 8,2 l 1.88,1.88",
            "M 9,7.13 V 6 a 3,3 0 1,1 6,0 v 1.13"
        )
    }

    val Check: ImageVector by lazy {
        outline("Check",
            "M 20,6 L 9,17 l -5,-5"
        )
    }

    val Close: ImageVector by lazy {
        outline("Close",
            "M 18,6 L 6,18",
            "m 6,6 l 12,12"
        )
    }

    val Contents: ImageVector by lazy {
        outline("Contents",
            "M 3,5 h 0.01",
            "M 3,12 h 0.01",
            "M 3,19 h 0.01",
            "M 8,5 h 13",
            "M 8,12 h 13",
            "M 8,19 h 13"
        )
    }

    val Back: ImageVector by lazy {
        outline("Back",
            "m 12,19 l -7,-7 l 7,-7",
            "M 19,12 H 5",
            autoMirror = true
        )
    }

    val Fullscreen: ImageVector by lazy {
        outline("Fullscreen",
            "M 8,3 H 5 a 2,2 0 0,0 -2,2 v 3",
            "M 21,8 V 5 a 2,2 0 0,0 -2,-2 h -3",
            "M 3,16 v 3 a 2,2 0 0,0 2,2 h 3",
            "M 16,21 h 3 a 2,2 0 0,0 2,-2 v -3"
        )
    }

    val ExitFullscreen: ImageVector by lazy {
        outline("ExitFullscreen",
            "M 8,3 v 3 a 2,2 0 0,1 -2,2 H 3",
            "M 21,8 h -3 a 2,2 0 0,1 -2,-2 V 3",
            "M 3,16 h 3 a 2,2 0 0,1 2,2 v 3",
            "M 16,21 v -3 a 2,2 0 0,1 2,-2 h 3"
        )
    }

    val Watch: ImageVector by lazy {
        outline("Watch",
            "M 15.033,9.44 a 0.647,0.647 0 0,1 0,1.12 l -4.065,2.352 a 0.645,0.645 0 0,1 -0.968,-0.56 V 7.648 a 0.645,0.645 0 0,1 0.967,-0.56 z",
            "M 12,17 v 4",
            "M 8,21 h 8",
            "M 4,3 H 20 A 2,2 0 0,1 22,5 V 15 A 2,2 0 0,1 20,17 H 4 A 2,2 0 0,1 2,15 V 5 A 2,2 0 0,1 4,3 Z"
        )
    }

    val Previous: ImageVector by lazy {
        outline("Previous",
            "M 17.971,4.285 A 2,2 0 0,1 21,6 v 12 a 2,2 0 0,1 -3.029,1.715 l -9.997,-5.998 a 2,2 0 0,1 -0.003,-3.432 z",
            "M 3,20 V 4"
        )
    }

    val Next: ImageVector by lazy {
        outline("Next",
            "M 21,4 v 16",
            "M 6.029,4.285 A 2,2 0 0,0 3,6 v 12 a 2,2 0 0,0 3.029,1.715 l 9.997,-5.998 a 2,2 0 0,0 0.003,-3.432 z"
        )
    }

    val Play: ImageVector by lazy {
        outline("Play",
            "M 5,5 a 2,2 0 0,1 3.008,-1.728 l 11.997,6.998 a 2,2 0 0,1 0.003,3.458 l -12,7 A 2,2 0 0,1 5,19 z"
        )
    }

    val Pause: ImageVector by lazy {
        outline("Pause",
            "M 15,3 H 18 A 1,1 0 0,1 19,4 V 20 A 1,1 0 0,1 18,21 H 15 A 1,1 0 0,1 14,20 V 4 A 1,1 0 0,1 15,3 Z",
            "M 6,3 H 9 A 1,1 0 0,1 10,4 V 20 A 1,1 0 0,1 9,21 H 6 A 1,1 0 0,1 5,20 V 4 A 1,1 0 0,1 6,3 Z"
        )
    }

    val Stop: ImageVector by lazy {
        outline("Stop",
            "M 5,3 H 19 A 2,2 0 0,1 21,5 V 19 A 2,2 0 0,1 19,21 H 5 A 2,2 0 0,1 3,19 V 5 A 2,2 0 0,1 5,3 Z"
        )
    }

    val Speaker: ImageVector by lazy {
        outline("Speaker",
            "M 11,4.702 a 0.705,0.705 0 0,0 -1.203,-0.498 L 6.413,7.587 A 1.4,1.4 0 0,1 5.416,8 H 3 a 1,1 0 0,0 -1,1 v 6 a 1,1 0 0,0 1,1 h 2.416 a 1.4,1.4 0 0,1 0.997,0.413 l 3.383,3.384 A 0.705,0.705 0 0,0 11,19.298 z",
            "M 16,9 a 5,5 0 0,1 0,6",
            "M 19.364,18.364 a 9,9 0 0,0 0,-12.728"
        )
    }

    val NextParagraph: ImageVector by lazy {
        outline("NextParagraph",
            "m 6,17 l 5,-5 l -5,-5",
            "m 13,17 l 5,-5 l -5,-5",
            autoMirror = true
        )
    }

    val Typography: ImageVector by lazy {
        outline("Typography",
            "m 2,16 l 4.039,-9.69 a 0.5,0.5 0 0,1 0.923,0 L 11,16",
            "M 22,9 v 7",
            "M 3.304,13 h 6.392",
            "M 15,12.5 a 3.5,3.5 0 1,0 7,0 a 3.5,3.5 0 1,0 -7,0 Z"
        )
    }

    val Follow: ImageVector by lazy {
        outline("Follow",
            "M 9,12 a 3,3 0 1,0 6,0 a 3,3 0 1,0 -6,0 Z",
            "M 3,7 V 5 a 2,2 0 0,1 2,-2 h 2",
            "M 17,3 h 2 a 2,2 0 0,1 2,2 v 2",
            "M 21,17 v 2 a 2,2 0 0,1 -2,2 h -2",
            "M 7,21 H 5 a 2,2 0 0,1 -2,-2 v -2"
        )
    }

}

private fun outline(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = "Peekturn$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply {
        paths.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
