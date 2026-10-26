package app.peekturn.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.peekturn.reader.model.*
import app.peekturn.reader.video.VideoItem
import app.peekturn.reader.video.VideoMetadata
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
    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()
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
            if (lastContent() == LastContent.Book(id)) clearLastContentPreference()
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
                check(prefs.edit().putLong("last_book", bookId).putString("last_content_kind", "book")
                    .putLong("last_content_id", bookId).commit()) { "Could not save last book" }
                refreshBooks()
            }
        }
    }
    fun lastBookId(): Long? = prefs.getLong("last_book", -1L).takeIf { it >= 0 }

    fun lastContent(): LastContent? {
        val id = prefs.getLong("last_content_id", -1L)
        return when (prefs.getString("last_content_kind", null)) {
            "book" -> id.takeIf { it > 0 }?.let { LastContent.Book(it) }
            "video" -> id.takeIf { it > 0 }?.let { LastContent.Video(it) }
            null -> lastBookId()?.let { LastContent.Book(it) } // Existing installations retain EPUB resume.
            else -> null
        }
    }

    private fun clearLastContentPreference() {
        check(prefs.edit().putString("last_content_kind", "none").remove("last_content_id").commit()) {
            "Could not clear last content"
        }
    }

    suspend fun clearLastContent(expected: LastContent) = withContext(Dispatchers.IO) {
        writes.withLock {
            if (lastContent() == expected) {
                clearLastContentPreference()
                if (expected is LastContent.Book) check(prefs.edit().remove("last_book").commit())
            }
        }
    }

    fun builtinBookSeeded(): Boolean = prefs.getBoolean("builtin_the_prophet_seeded", false)

    suspend fun markBuiltinBookSeeded() = withContext(Dispatchers.IO) {
        writes.withLock {
            check(prefs.edit().putBoolean("builtin_the_prophet_seeded", true).commit()) {
                "Could not record the included book"
            }
        }
    }
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
        writes.withLock {
            if (lastContent() is LastContent.Book) clearLastContentPreference()
            check(prefs.edit().remove("last_book").commit()) { "Could not clear last book" }
        }
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

    suspend fun findVideo(id: Long): VideoItem? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("videos", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.video() else null
        }
    }

    suspend fun findVideoBySource(videoId: String): VideoItem? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("videos", null, "video_id=?", arrayOf(videoId), null, null, null).use { c ->
            if (c.moveToFirst()) c.video() else null
        }
    }

    /**
     * A video's public identity ([VideoMetadata.videoId]) owns at most one row. Re-importing the
     * same video refreshes the source-provided metadata only: the watching position, the last watch
     * time and the chosen caption track belong to the user and survive. An unknown duration (0)
     * never replaces a duration that was already learned.
     */
    suspend fun upsertVideo(metadata: VideoMetadata): VideoItem = withContext(Dispatchers.IO) {
        writes.withLock {
            require(metadata.videoId.isNotBlank()) { "A video identity is required" }
            val database = db.writableDatabase
            val duration = metadata.durationMs.coerceAtLeast(0L)
            database.beginTransaction()
            try {
                val existing = database.query("videos", null, "video_id=?", arrayOf(metadata.videoId), null, null, null)
                    .use { c -> if (c.moveToFirst()) c.video() else null }
                if (existing == null) {
                    database.insertOrThrow("videos", null, ContentValues().apply {
                        put("video_id", metadata.videoId)
                        put("title", metadata.title)
                        put("author", metadata.author)
                        put("duration_ms", duration)
                        put("thumbnail_url", metadata.thumbnailUrl)
                        put("position_ms", 0L)
                        put("last_watched_at", 0L)
                        putNull("selected_track_id")
                    })
                } else {
                    val keptDuration = if (duration > 0) duration else existing.durationMs
                    database.update("videos", ContentValues().apply {
                        put("title", metadata.title)
                        put("author", metadata.author)
                        put("duration_ms", keptDuration)
                        put("thumbnail_url", metadata.thumbnailUrl)
                    }, "video_id=?", arrayOf(metadata.videoId))
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            refreshVideos()
            _videos.value.firstOrNull { it.videoId == metadata.videoId }
                ?: throw IllegalStateException("Could not save video")
        }
    }

    suspend fun saveVideoPosition(id: Long, positionMs: Long) = withContext(Dispatchers.IO) {
        writes.withLock {
            val database = db.writableDatabase
            // A position cannot run past a known duration; an unknown duration stays unbounded.
            val duration = database.query("videos", arrayOf("duration_ms"), "id=?", arrayOf(id.toString()), null, null, null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val position = positionMs.coerceAtLeast(0L).let { if (duration > 0) it.coerceAtMost(duration) else it }
            if (database.update("videos", ContentValues().apply { put("position_ms", position) }, "id=?", arrayOf(id.toString())) > 0) {
                refreshVideos()
            }
        }
    }

    suspend fun markVideoOpened(id: Long, remember: Boolean = true) = withContext(Dispatchers.IO) {
        writes.withLock {
            val values = ContentValues().apply { put("last_watched_at", System.currentTimeMillis()) }
            if (db.writableDatabase.update("videos", values, "id=?", arrayOf(id.toString())) > 0) {
                if (remember) check(prefs.edit().putString("last_content_kind", "video").putLong("last_content_id", id).commit()) {
                    "Could not save last video"
                }
                refreshVideos()
            }
        }
    }

    suspend fun selectVideoTrack(id: Long, trackId: String?, explicit: Boolean = true) = withContext(Dispatchers.IO) {
        writes.withLock {
            val values = ContentValues().apply {
                put("selected_track_id", trackId)
                put("track_explicit", if (explicit) 1 else 0)
            }
            if (db.writableDatabase.update("videos", values, "id=?", arrayOf(id.toString())) > 0) refreshVideos()
        }
    }

    suspend fun selectVideoAudioMode(id: Long, audioOnly: Boolean) =
        selectVideoPresentation(id, audioOnly, readMode = false)

    suspend fun selectVideoPresentation(id: Long, audioOnly: Boolean, readMode: Boolean) = withContext(Dispatchers.IO) {
        require(!readMode || audioOnly)
        writes.withLock {
            val values = ContentValues().apply {
                put("audio_only", if (audioOnly) 1 else 0)
                put("read_mode", if (readMode) 1 else 0)
            }
            check(db.writableDatabase.update("videos", values, "id=?", arrayOf(id.toString())) == 1) {
                "Video playback preference was not saved"
            }
            refreshVideos()
        }
    }

    suspend fun deleteVideo(id: Long) = withContext(Dispatchers.IO) {
        writes.withLock {
            db.writableDatabase.delete("videos", "id=?", arrayOf(id.toString()))
            if (lastContent() == LastContent.Video(id)) clearLastContentPreference()
            refreshVideos()
            refreshStats()
        }
    }

    suspend fun addVideoTime(id: Long, date: String, seconds: Long) = withContext(Dispatchers.IO) {
        if (seconds <= 0) return@withContext
        writes.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                database.execSQL(
                    "INSERT OR IGNORE INTO video_daily(video_id,date,seconds) SELECT ?,?,0 WHERE EXISTS(SELECT 1 FROM videos WHERE id=?)",
                    arrayOf<Any>(id, date, id)
                )
                database.execSQL("UPDATE video_daily SET seconds=seconds+? WHERE video_id=? AND date=?", arrayOf<Any>(seconds, id, date))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
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
        var rewriteProvider = false
        var rewriteObsoleteSpeech = false
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
        // An enum name Gson cannot resolve would fail the whole restore, so an unrecognised or
        // wrongly typed stored provider is repaired to the documented default before conversion.
        // The check reads the stored value, not the restored tree: restoreFields already discards a
        // wrongly typed field, which would otherwise leave the default looking valid.
        val storedProvider = source?.get("sentenceSpeechProvider")
        val validProvider = storedProvider == null || (storedProvider.isJsonPrimitive &&
            storedProvider.asJsonPrimitive.isString &&
            SentenceSpeechProvider.entries.any { it.name == storedProvider.asString })
        if (storedProvider != null && !validProvider) {
            restored.addProperty("sentenceSpeechProvider", SentenceSpeechProvider.DEFAULT.name)
            invalid = true
            rewriteProvider = true
        }
        // The in-app engine and voice picker is gone; the system engine owns both. An older stored
        // selection is neither a damaged preference nor a choice to keep, so it is silently dropped
        // by the next rewrite instead of lingering as a dormant pinned voice.
        if (source?.has("systemSpeech") == true) rewriteObsoleteSpeech = true
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
        if (migrating || rewriteProfiles || rewriteProvider || rewriteObsoleteSpeech || source?.has("model") == true ||
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
    private fun refresh() { refreshBooks(); refreshVideos(); refreshStats() }
    private fun refreshBooks() {
        _books.value = db.readableDatabase.query("books", null, null, null, null, null, "last_read_at DESC,id DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.book()) }
        }
    }
    private fun refreshVideos() {
        _videos.value = db.readableDatabase.query("videos", null, null, null, null, null, "last_watched_at DESC,id DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.video()) }
        }
    }
    private fun refreshStats() {
        // Both tables keep their own rows; a local day is the sum of reading and video time, and a
        // book id and a video id never mix even when they happen to be the same number.
        val days = db.readableDatabase.rawQuery(
            "SELECT date,SUM(seconds) FROM (" +
                "SELECT date,seconds FROM reading_daily UNION ALL SELECT date,seconds FROM video_daily" +
                ") GROUP BY date ORDER BY date", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(ReadingDay(c.getString(0), c.getLong(1))) }
        }
        val books = db.readableDatabase.rawQuery("SELECT book_id,SUM(seconds) FROM reading_daily GROUP BY book_id", null).use { c ->
            buildList { while (c.moveToNext()) add(BookReadingTime(c.getLong(0), c.getLong(1))) }
        }
        val videos = db.readableDatabase.rawQuery("SELECT video_id,SUM(seconds) FROM video_daily GROUP BY video_id", null).use { c ->
            buildList { while (c.moveToNext()) add(VideoReadingTime(c.getLong(0), c.getLong(1))) }
        }
        _stats.value = ReadingStats(days, books, videos)
    }
    private fun android.database.Cursor.video() = VideoItem(
        id = getLong(getColumnIndexOrThrow("id")),
        videoId = getString(getColumnIndexOrThrow("video_id")),
        title = getString(getColumnIndexOrThrow("title")),
        author = getString(getColumnIndexOrThrow("author")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        thumbnailUrl = getColumnIndexOrThrow("thumbnail_url")
            .let { if (isNull(it)) null else getString(it) },
        positionMs = getLong(getColumnIndexOrThrow("position_ms")),
        lastWatchedAt = getLong(getColumnIndexOrThrow("last_watched_at")),
        selectedTrackId = getColumnIndexOrThrow("selected_track_id")
            .let { if (isNull(it)) null else getString(it) },
        trackExplicit = getInt(getColumnIndexOrThrow("track_explicit")) != 0,
        audioOnly = getColumnIndexOrThrow("audio_only").let { if (isNull(it)) null else getInt(it) != 0 },
        readMode = getInt(getColumnIndexOrThrow("read_mode")) != 0
    )
    private fun android.database.Cursor.book() = Book(
        id = getLong(getColumnIndexOrThrow("id")), title = getString(getColumnIndexOrThrow("title")),
        author = getString(getColumnIndexOrThrow("author")), filePath = getString(getColumnIndexOrThrow("file_path")),
        coverPath = getString(getColumnIndexOrThrow("cover_path")), locatorJson = getString(getColumnIndexOrThrow("locator")),
        progression = getDouble(getColumnIndexOrThrow("progression")), lastReadAt = getLong(getColumnIndexOrThrow("last_read_at"))
    )
    private class Database(context: Context) : SQLiteOpenHelper(context, "peekturn.db", null, 5) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,author TEXT NOT NULL,file_path TEXT NOT NULL,cover_path TEXT,locator TEXT,progression REAL NOT NULL DEFAULT 0,last_read_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE reading_daily(book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book_id,date))")
            createVideoTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (newVersion > 5) error("No migration from $oldVersion to $newVersion")
            if (oldVersion < 2) createVideoTables(db)
            else {
                if (oldVersion < 3) db.execSQL("ALTER TABLE videos ADD COLUMN track_explicit INTEGER NOT NULL DEFAULT 0")
                if (oldVersion < 4) db.execSQL("ALTER TABLE videos ADD COLUMN audio_only INTEGER")
                if (oldVersion < 5) db.execSQL("ALTER TABLE videos ADD COLUMN read_mode INTEGER NOT NULL DEFAULT 0")
            }
        }
        private fun createVideoTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS videos(id INTEGER PRIMARY KEY AUTOINCREMENT,video_id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,author TEXT NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,thumbnail_url TEXT,position_ms INTEGER NOT NULL DEFAULT 0,last_watched_at INTEGER NOT NULL DEFAULT 0,selected_track_id TEXT,track_explicit INTEGER NOT NULL DEFAULT 0,audio_only INTEGER,read_mode INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS video_daily(video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,date TEXT NOT NULL,seconds INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(video_id,date))")
        }
    }
}
