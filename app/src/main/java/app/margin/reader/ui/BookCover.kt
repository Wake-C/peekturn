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
    val frame = modifier.aspectRatio(2f / 3f).border(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (cover != null) {
        Image(cover!!, contentDescription = "Cover of ${book.title}", modifier = frame, contentScale = ContentScale.Crop)
    } else {
        BoxWithConstraints(frame.background(MaterialTheme.colorScheme.surfaceVariant)) {
            val compact = maxWidth < 100.dp
            Column(
                Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(book.title, fontFamily = FontFamily.Serif,
                    fontSize = if (compact) 12.sp else 22.sp, lineHeight = if (compact) 14.sp else 26.sp,
                    color = FocusColors.Text, maxLines = if (compact) 4 else 5, overflow = TextOverflow.Ellipsis)
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    Box(Modifier.width(if (compact) 16.dp else 24.dp).height(1.dp).background(FocusColors.Accent))
                    if (!compact) Text(book.author.ifBlank { "Unknown author" }, style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
