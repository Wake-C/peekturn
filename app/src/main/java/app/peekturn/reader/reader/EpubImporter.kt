package app.peekturn.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.model.Book
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication

class EpubImporter(context: Context, private val engine: EpubEngine, private val store: PeekturnStore) {
    private val context = context.applicationContext

    /** Imports a document the system picker or a share sheet handed over as a content URI. */
    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val archive = stagedArchive()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output -> copyLimited(input, output) }
            } ?: throw IOException("The selected document cannot be read.")
            adopt(archive)
        } catch (e: Exception) {
            discard(archive)
            throw e
        }
    }

    /** Imports an EPUB the app produced itself, such as a page converted from a URL. */
    suspend fun importFile(source: File): Book = withContext(Dispatchers.IO) {
        val archive = stagedArchive()
        try {
            source.inputStream().use { input ->
                archive.outputStream().use { output -> copyLimited(input, output) }
            }
            adopt(archive)
        } catch (e: Exception) {
            discard(archive)
            throw e
        } finally {
            source.delete()
        }
    }

    private fun stagedArchive(): File {
        val directory = File(context.filesDir, "books").apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.epub")
    }

    private fun coverFor(archive: File): File =
        File(archive.parentFile, archive.nameWithoutExtension + ".jpg")

    private fun discard(archive: File) {
        archive.delete()
        coverFor(archive).delete()
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ARCHIVE_BYTES) throw IOException("EPUB exceeds the 256 MB import limit.")
            output.write(buffer, 0, count)
        }
    }

    private suspend fun adopt(archive: File): Book {
        validateArchive(archive)
        val publication = engine.open(archive)
        try {
            val cover = coverFor(archive)
            readCover(publication)?.let { bitmap ->
                try {
                    val scale = minOf(1f, 1000f / maxOf(bitmap.width, bitmap.height))
                    val thumbnail = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                    try {
                        cover.outputStream().use {
                            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, it)) {
                                throw IOException("The EPUB cover could not be saved.")
                            }
                        }
                    } finally {
                        if (thumbnail !== bitmap) thumbnail.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            return withContext(NonCancellable) {
                store.addBook(
                    title = publication.metadata.title?.takeIf { it.isNotBlank() } ?: "Untitled book",
                    author = publication.metadata.authors.joinToString(", ") { it.name },
                    filePath = archive.absolutePath,
                    coverPath = cover.takeIf { it.isFile }?.absolutePath
                )
            }
        } finally {
            publication.close()
        }
    }

    private suspend fun readCover(publication: Publication): Bitmap? {
        val link = publication.linkWithRel("cover") ?: return null
        val resource = publication.get(link) ?: return null
        val bytes = try { resource.read(0L..(8L * 1024 * 1024)).getOrNull() } finally { resource.close() }
            ?: return null
        if (bytes.size > 8 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private suspend fun validateArchive(file: File) {
        ZipFile(file).use { zip ->
            var expanded = 0L
            var entries = 0
            val buffer = ByteArray(64 * 1024)
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                entries++
                if (entries > 20000 || entry.size < 0 || entry.size > 32L * 1024 * 1024) {
                    throw IOException("This EPUB contains an oversized resource or too many resources.")
                }
                var resourceBytes = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        resourceBytes += count
                        expanded += count
                        if (resourceBytes > 32L * 1024 * 1024 || expanded > 512L * 1024 * 1024) {
                            throw IOException("This EPUB exceeds the expanded resource limit.")
                        }
                    }
                }
                if (resourceBytes != entry.size) throw IOException("This EPUB contains a damaged resource.")
            }
            val mime = zip.getEntry("mimetype") ?: throw IOException("The document is not an EPUB.")
            if (mime.size !in 1..64 || zip.getInputStream(mime).use { it.readBytes().toString(Charsets.US_ASCII).trim() } != "application/epub+zip") {
                throw IOException("The document is not an EPUB.")
            }
        }
    }

    companion object { internal const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024 }
}
