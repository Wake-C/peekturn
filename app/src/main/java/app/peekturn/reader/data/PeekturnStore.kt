package app.peekturn.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.peekturn.reader.model.*
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
import java.util.UUID

private const val MAX_PAGE_DENSITIES = 16

class PeekturnStore internal constructor(private val context: Context, private val vault: CredentialVault) {
    constructor(context: Context) : this(context, AndroidCredentialVault())
    private val db = Database(context)
    private val prefs = context.getSharedPreferences("peekturn", Context.MODE_PRIVATE)
    private val profileKeyPrefix = "profile_key_cipher:"
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
                    Log.w("PeekturnStore", "Could not remove private book resource")
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
    /**
     * Display-only cache of characters per page per layout signature. Page counts are derivatives,
     * never reading positions, and this only lets a book page counter appear before the reader meets
     * a full chapter in the current layout.
     */
    fun cachedPageDensity(): Map<String, Double> = runCatching {
        prefs.getString("page_density", null)?.let { json ->
            JsonParser.parseString(json).asJsonObject.entrySet().mapNotNull { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    value.asDouble.takeIf { it.isFinite() && it > 0 }?.let { key to it }
                } else null
            }.toMap()
        }
    }.getOrNull().orEmpty()
    suspend fun cachePageDensity(signature: String, charsPerPage: Double) = withContext(Dispatchers.IO) {
        if (!charsPerPage.isFinite() || charsPerPage <= 0) return@withContext
        writes.withLock {
            val current = cachedPageDensity().toMutableMap()
            current[signature] = charsPerPage
            while (current.size > MAX_PAGE_DENSITIES) current.remove(current.keys.first())
            check(prefs.edit().putString("page_density", gson.toJson(current)).commit()) {
                "Could not save page density"
            }
        }
    }
    suspend fun clearLastBook() = withContext(Dispatchers.IO) {
        writes.withLock { check(prefs.edit().remove("last_book").commit()) { "Could not clear last book" } }
    }
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        writes.withLock {
            val transformed = transform(_settings.value)
            val replacement = if (transformed.profiles.isEmpty()) LlmProfile(UUID.randomUUID().toString(), ModelConfig()) else null
            val normalized = normalizeSettings(
                if (replacement == null) transformed
                else transformed.copy(profiles = listOf(replacement), activeProfileId = replacement.id)
            )
            persistSettings(normalized, previous = _settings.value)
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
    private fun normalizeSettings(settings: AppSettings): AppSettings {
        val profiles = settings.profiles.filter { it.id.isNotBlank() }.distinctBy { it.id }
            .ifEmpty { AppSettings().profiles }
        return settings.copy(
            profiles = profiles.map { profile ->
                val temperature = profile.config.temperature
                val sanitized = ModelConfig.sanitizeTemperature(temperature)
                if (sanitized == temperature) profile else profile.copy(config = profile.config.copy(temperature = sanitized))
            },
            activeProfileId = settings.activeProfileId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
            accent = if (settings.accent == "UK") "UK" else "US",
            appTheme = when (settings.appTheme) {
                "dark", "light", "warm", "oled", "sage" -> settings.appTheme
                else -> "dark"
            },
            uiLanguage = when (settings.uiLanguage) { "en", "zh" -> settings.uiLanguage; else -> "system" },
            // Readium accepts letter spacing in em from 0 to 1; anything else is repaired rather
            // than failing the whole typography preference.
            reading = settings.reading.copy(
                letterSpacing = settings.reading.letterSpacing
                    .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            )
        )
    }

    private fun persistSettings(
        settings: AppSettings,
        preservedCiphers: Map<String, String> = emptyMap(),
        previous: AppSettings? = null
    ) {
        val safe = gson.toJsonTree(settings).asJsonObject
        val editor = prefs.edit()
        val retainedKeys = settings.profiles.map { profileKeyPrefix + it.id }.toSet()
        prefs.all.keys.filter { it.startsWith(profileKeyPrefix) && it !in retainedKeys }.forEach(editor::remove)
        settings.profiles.forEachIndexed { index, profile ->
            safe.getAsJsonArray("profiles")[index].asJsonObject.getAsJsonObject("config").remove("apiKey")
            val key = profile.config.apiKey
            val unchanged = previous?.profiles?.any { it.id == profile.id && it.config.apiKey == key } == true
            val cipher = preservedCiphers[profile.id]
                ?: (if (unchanged) prefs.getString(profileKeyPrefix + profile.id, null) else null)
                ?: key.takeIf { it.isNotBlank() }?.let(vault::encrypt)
            if (cipher == null) editor.remove(profileKeyPrefix + profile.id)
            else editor.putString(profileKeyPrefix + profile.id, cipher)
        }
        check(editor.putString("settings", gson.toJson(safe)).remove("model_key_cipher").commit()) {
            "Could not save preferences"
        }
    }

    private fun readSettings(): AppSettings {
        var invalid = false
        var rewriteProfiles = false
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
        val source = try {
            prefs.getString("settings", null)?.let { JsonParser.parseString(it).asJsonObject }
        } catch (e: Exception) {
            Log.w("PeekturnStore", "Invalid preferences: ${e.javaClass.simpleName}")
            invalid = true
            null
        }
        val restored = gson.toJsonTree(AppSettings()).asJsonObject
        restored.remove("profiles")
        source?.let { restoreFields(restored, it) }
        val legacyReading = source?.get("reading")?.takeIf { it.isJsonObject }?.asJsonObject
        if (legacyReading != null && !legacyReading.has("immersive")) {
            fun legacyBarHidden(name: String): Boolean {
                val value = legacyReading.get(name) ?: return true
                if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) return value.asBoolean
                invalid = true
                return true
            }
            val hideStatus = legacyBarHidden("hideStatusBar")
            val hideNavigation = legacyBarHidden("hideNavigationBar")
            restored.getAsJsonObject("reading").addProperty("immersive", hideStatus && hideNavigation)
        }
        var saved = gson.fromJson(restored, AppSettings::class.java)
        val profiles = mutableListOf<LlmProfile>()
        // Obsolete thinking flags and unusable stored temperatures are rewritten from the current contract.
        fun invalidTemperature(raw: JsonObject): Boolean {
            val value = raw.get("temperature") ?: return false
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return true
            return !ModelConfig.validTemperature(value.asJsonPrimitive.asDouble)
        }
        val profileValues = source?.get("profiles")
        if (profileValues != null && profileValues.isJsonArray) {
            for (value in profileValues.asJsonArray) {
                if (!value.isJsonObject) { invalid = true; continue }
                val entry = value.asJsonObject
                val id = entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (id.isNullOrBlank() || profiles.any { it.id == id }) { invalid = true; continue }
                val config = gson.toJsonTree(ModelConfig()).asJsonObject
                config.remove("apiKey")
                val rawConfig = entry.get("config")?.takeIf { it.isJsonObject }?.asJsonObject
                if (rawConfig != null) {
                    restoreFields(config, rawConfig)
                    if (rawConfig.has("thinking")) rewriteProfiles = true
                    if (invalidTemperature(rawConfig)) { invalid = true; rewriteProfiles = true }
                } else invalid = true
                profiles.add(LlmProfile(id, gson.fromJson(config, ModelConfig::class.java).copy(apiKey = "")))
            }
        } else if (profileValues != null) invalid = true
        val legacyCipher = prefs.getString("model_key_cipher", null)?.takeIf { it.isNotBlank() }
        val migrating = (source != null && profileValues == null) || (profiles.isEmpty() && legacyCipher != null)
        if (migrating) {
            val config = gson.toJsonTree(ModelConfig()).asJsonObject
            config.remove("apiKey")
            val legacy = source?.get("model")
            if (legacy?.isJsonObject == true) restoreFields(config, legacy.asJsonObject)
            else if (legacy != null) invalid = true
            profiles.add(LlmProfile("default", gson.fromJson(config, ModelConfig::class.java).copy(apiKey = "")))
        }
        val ciphers = mutableMapOf<String, String>()
        saved = normalizeSettings(saved.copy(profiles = profiles))
        saved = saved.copy(profiles = saved.profiles.map { profile ->
            val cipher = (if (migrating && profile.id == "default") legacyCipher else null)
                ?: prefs.getString(profileKeyPrefix + profile.id, null)
            if (!cipher.isNullOrBlank()) ciphers[profile.id] = cipher
            val key = try {
                cipher?.takeIf { it.isNotBlank() }?.let(vault::decrypt).orEmpty()
            } catch (e: Exception) {
                Log.w("PeekturnStore", "Credential unavailable: ${e.javaClass.simpleName}")
                _error.value = "A saved API key could not be unlocked. Edit its profile in Settings."
                ""
            }
            profile.copy(config = profile.config.copy(apiKey = key))
        })
        if (invalid) _error.value = "Some preferences could not be restored. Please check Settings."
        if (migrating || rewriteProfiles || source?.has("model") == true ||
            profileValues?.takeIf { it.isJsonArray }?.asJsonArray?.any {
                it.isJsonObject && it.asJsonObject.get("config")?.takeIf { config -> config.isJsonObject }
                    ?.asJsonObject?.has("apiKey") == true
            } == true
        ) {
            try { persistSettings(saved, ciphers) } catch (_: Exception) {
                _error.value = "Preferences could not be migrated. Please save them again in Settings."
            }
        }
        return saved
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
    private class Database(context: Context) : SQLiteOpenHelper(context, "peekturn.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,author TEXT NOT NULL,file_path TEXT NOT NULL,cover_path TEXT,locator TEXT,progression REAL NOT NULL DEFAULT 0,last_read_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE reading_daily(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book_id,date))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No migration from $oldVersion to $newVersion")
    }
}
