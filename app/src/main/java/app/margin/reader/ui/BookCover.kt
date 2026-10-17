package app.margin.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.margin.reader.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes a sampled local cover off the UI thread, never a full-resolution EPUB image. */
@Composable
internal fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val target = with(density) { 200.dp.roundToPx() }
    val cover by produceState<ImageBitmap?>(null, book.coverPath, target) {
        value = withContext(Dispatchers.IO) {
            val path = book.coverPath ?: return@withContext null
            try {
                val file = File(path)
                if (!file.isFile || file.length() > 20L * 1024 * 1024) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target * 2) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val frame = modifier.aspectRatio(2f / 3f)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
        .semantics { contentDescription = "Cover of ${book.title}" }
    if (cover != null) {
        Image(cover!!, contentDescription = null, modifier = frame, contentScale = ContentScale.Fit)
    } else {
        BoxWithConstraints(frame) {
            val compact = maxWidth < 100.dp
            val initial = remember(book.title) {
                book.title.trim().let {
                    if (it.isEmpty()) "M" else String(Character.toChars(it.codePointAt(0))).uppercase()
                }
            }
            Column(
                Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(initial, modifier = Modifier.weight(1f), fontFamily = FontFamily.Serif,
                    fontSize = if (compact) 24.sp else 52.sp, lineHeight = if (compact) 28.sp else 60.sp,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Clip)
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    // The focus card prints the author directly beneath this cover, so the cover
                    // carries only the monogram and its rule instead of repeating the metadata.
                    Box(Modifier.width(if (compact) 16.dp else 24.dp).height(1.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}
