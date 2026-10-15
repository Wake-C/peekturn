package app.margin.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.margin.reader.model.DictionaryEntry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Exact, case/whitespace-normalized lookup. Null means absent, not an extraction failure. */
class OfflineDictionary(context: Context) {
    private val app = context.applicationContext
    private val extraction = Mutex()
    private var installed: File? = null

    suspend fun lookup(selection: String): DictionaryEntry? = withContext(Dispatchers.IO) {
        val query = normalizeDictionaryQuery(selection)
        if (query.isEmpty() || query.none { it in 'a'..'z' }) return@withContext null
        val file = extraction.withLock {
            installed ?: install().also { installed = it }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT word, phonetic, pos, definition, translation FROM entries WHERE query = ? LIMIT 1",
                arrayOf(query)
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                DictionaryEntry(
                    word = cursor.getString(0),
                    phonetic = cursor.getString(1),
                    partOfSpeech = cursor.getString(2),
                    meaning = listOf(cursor.getString(3), cursor.getString(4))
                        .filter { it.isNotBlank() }.joinToString("\n\n")
                )
            }
        }
    }

    private fun install(): File {
        val provenance = app.assets.open("dict/provenance.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val expected = provenance.getString("sqlite_sha256")
        val destination = File(app.noBackupFilesDir, "dictionary-${expected.take(16)}.sqlite")
        if (destination.isFile && destination.length() == provenance.getLong("sqlite_bytes")) {
            return destination
        }
        val temporary = File.createTempFile("dictionary-", ".tmp", app.noBackupFilesDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            // .dict preserves gzip bytes; Android asset packaging treats .gz specially.
            GZIPInputStream(app.assets.open("dict/dictionary.dict")).use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Offline dictionary asset failed its integrity check." }
            check(temporary.renameTo(destination)) { "Could not install the offline dictionary." }
            return destination
        } finally {
            temporary.delete()
        }
    }
}

private val dictionaryWhitespace = Regex("[\\s\\p{Z}\\u0085]+")

internal fun normalizeDictionaryQuery(selection: String): String =
    selection.replace(dictionaryWhitespace, " ").trim().lowercase(Locale.ROOT)
