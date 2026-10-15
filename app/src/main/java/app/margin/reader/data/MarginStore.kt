package app.margin.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.margin.reader.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MarginStore(private val context: Context) {
    private val db = Database(context)
    private val prefs = context.getSharedPreferences("margin", Context.MODE_PRIVATE)
    private val vault = CredentialVault()
    private val gson = Gson()
    private val writes = Mutex()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _stats = MutableStateFlow(ReadingStats())
    val stats: StateFlow<ReadingStats> = _stats.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { writes.withLock { refresh() } }
    suspend fun findBook(id: Long): Book? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("books", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.book() else null
        }
    }
    suspend fun addBook(title: String, author: String, filePath: String, coverPath: String?): Book = withContext(Dispatchers.IO) {
        writes.withLock {
            require(File(filePath).isFile) { "Imported EPUB is missing" }
            val values = ContentValues().apply {
                put("title", title); put("author", author); put("file_path", filePath)
                put("cover_path", coverPath); put("progression", 0.0); put("last_read_at", 0L)
            }
            val id = db.writableDatabase.insertOrThrow("books", null, values)
            refresh()
            _books.value.first { it.id == id }
        }
    }
    suspend fun deleteBook(id: Long) = withContext(Dispatchers.IO) {
        writes.withLock {
            val book = db.readableDatabase.query("books", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
                if (c.moveToFirst()) c.book() else null
            } ?: return@withLock
            db.writableDatabase.delete("books", "id=?", arrayOf(id.toString()))
            if (lastBookId() == id) check(prefs.edit().remove("last_book").commit()) { "Could not clear last book" }
            for (path in listOfNotNull(book.filePath, book.coverPath)) {
                val file = File(path).canonicalFile
                if (file.path.startsWith(context.filesDir.canonicalPath + File.separator) && file.exists() && !file.delete()) {
                    Log.w("MarginStore", "Could not remove private book resource")
                    _error.value = "Book removed, but a local resource could not be cleaned up."
                }
            }
            refresh()
        }
    }
    suspend fun savePosition(bookId: Long, locatorJson: String, progression: Double) = withContext(Dispatchers.IO) {
        writes.withLock {
            val values = ContentValues().apply {
                put("locator", locatorJson)
                put("progression", progression.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0)
                put("last_read_at", System.currentTimeMillis())
            }
            db.writableDatabase.update("books", values, "id=?", arrayOf(bookId.toString()))
            refreshBooks()
        }
    }
    suspend fun markOpened(bookId: Long) = withContext(Dispatchers.IO) {
        writes.withLock {
            val values = ContentValues().apply { put("last_read_at", System.currentTimeMillis()) }
            if (db.writableDatabase.update("books", values, "id=?", arrayOf(bookId.toString())) > 0) {
                check(prefs.edit().putLong("last_book", bookId).commit()) { "Could not save last book" }
                refreshBooks()
            }
        }
    }
    fun lastBookId(): Long? = prefs.getLong("last_book", -1L).takeIf { it >= 0 }
    suspend fun clearLastBook() = withContext(Dispatchers.IO) {
        writes.withLock { check(prefs.edit().remove("last_book").commit()) { "Could not clear last book" } }
    }
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        writes.withLock {
            val settings = transform(_settings.value)
            val normalized = settings.copy(accent = if (settings.accent == "UK") "UK" else "US")
            val key = normalized.model.apiKey
            val encrypted = if (key.isBlank()) "" else vault.encrypt(key)
            val safe = normalized.copy(model = normalized.model.copy(apiKey = ""))
            check(prefs.edit().putString("settings", gson.toJson(safe)).putString("model_key_cipher", encrypted).commit()) {
                "Could not save preferences"
            }
            _settings.value = normalized
        }
    }
    suspend fun addReadingTime(bookId: Long, date: String, seconds: Long) = withContext(Dispatchers.IO) {
        if (seconds <= 0) return@withContext
        writes.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                database.execSQL("INSERT OR IGNORE INTO reading_daily(book_id,date,seconds) SELECT ?,?,0 WHERE EXISTS(SELECT 1 FROM books WHERE id=?)", arrayOf<Any>(bookId, date, bookId))
                database.execSQL("UPDATE reading_daily SET seconds=seconds+? WHERE book_id=? AND date=?", arrayOf<Any>(seconds, bookId, date))
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            refreshStats()
        }
    }
    private fun readSettings(): AppSettings {
        val saved = try {
            prefs.getString("settings", null)?.let { json ->
                val restored = gson.toJsonTree(AppSettings()).asJsonObject
                var invalid = false
                fun restoreFields(target: JsonObject, source: JsonObject) {
                    for ((name, fallback) in target.entrySet()) {
                        val value = source.get(name) ?: continue
                        if (fallback.isJsonObject && value.isJsonObject) {
                            restoreFields(fallback.asJsonObject, value.asJsonObject)
                        } else if (fallback.isJsonPrimitive && value.isJsonPrimitive) {
                            val expected = fallback.asJsonPrimitive
                            val actual = value.asJsonPrimitive
                            val compatible = when {
                                expected.isBoolean -> actual.isBoolean
                                expected.isString -> actual.isString
                                expected.isNumber -> actual.isNumber && actual.asDouble.isFinite()
                                else -> false
                            }
                            if (compatible) target.add(name, value) else invalid = true
                        } else invalid = true
                    }
                }
                restoreFields(restored, JsonParser.parseString(json).asJsonObject)
                if (invalid) _error.value = "Some preferences could not be restored. Please check Settings."
                gson.fromJson(restored, AppSettings::class.java)
            } ?: AppSettings()
        } catch (e: Exception) {
            Log.w("MarginStore", "Invalid preferences: ${e.javaClass.simpleName}")
            _error.value = "Preferences could not be restored. Please check Settings."
            AppSettings()
        }
        val key = try {
            prefs.getString("model_key_cipher", null)?.takeIf { it.isNotBlank() }?.let(vault::decrypt).orEmpty()
        } catch (e: Exception) {
            Log.w("MarginStore", "Credential unavailable: ${e.javaClass.simpleName}")
            _error.value = "Your saved API key could not be unlocked. Enter it again in Settings."
            ""
        }
        return saved.copy(model = saved.model.copy(apiKey = key))
    }
    private fun refresh() { refreshBooks(); refreshStats() }
    private fun refreshBooks() {
        _books.value = db.readableDatabase.query("books", null, null, null, null, null, "last_read_at DESC,id DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.book()) }
        }
    }
    private fun refreshStats() {
        val days = db.readableDatabase.rawQuery("SELECT date,SUM(seconds) FROM reading_daily GROUP BY date ORDER BY date", null).use { c ->
            buildList { while (c.moveToNext()) add(ReadingDay(c.getString(0), c.getLong(1))) }
        }
        val books = db.readableDatabase.rawQuery("SELECT book_id,SUM(seconds) FROM reading_daily GROUP BY book_id", null).use { c ->
            buildList { while (c.moveToNext()) add(BookReadingTime(c.getLong(0), c.getLong(1))) }
        }
        _stats.value = ReadingStats(days, books)
    }
    private fun android.database.Cursor.book() = Book(
        id = getLong(getColumnIndexOrThrow("id")), title = getString(getColumnIndexOrThrow("title")),
        author = getString(getColumnIndexOrThrow("author")), filePath = getString(getColumnIndexOrThrow("file_path")),
        coverPath = getString(getColumnIndexOrThrow("cover_path")), locatorJson = getString(getColumnIndexOrThrow("locator")),
        progression = getDouble(getColumnIndexOrThrow("progression")), lastReadAt = getLong(getColumnIndexOrThrow("last_read_at"))
    )
    private class Database(context: Context) : SQLiteOpenHelper(context, "margin.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,author TEXT NOT NULL,file_path TEXT NOT NULL,cover_path TEXT,locator TEXT,progression REAL NOT NULL DEFAULT 0,last_read_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE reading_daily(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book_id,date))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No migration from $oldVersion to $newVersion")
    }
}
