package app.peekturn.reader.narration

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.peekturn.reader.model.SentenceSpeechProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a whole-book saved preparation stands. Only [READY] is playable; every other state is a
 * resumable or failed preparation that owns its private clips.
 */
internal enum class PreparedAudioStatus {
    INDEXING,
    PREPARING,
    PAUSED,
    READY,
    FAILED
}

/** Display-safe public metadata for one saved preparation; it never contains book text or paths. */
internal data class PreparedAudioVersion(
    val id: String,
    val bookId: Long,
    val bookFingerprint: String,
    val provider: SentenceSpeechProvider,
    val accent: String,
    val configuration: String,
    val voiceLabel: String,
    val status: PreparedAudioStatus,
    val completed: Int,
    val total: Int,
    val bytes: Long,
    val error: String? = null
)

/** One indexed sentence and the real Readium locator that reaches it; [text] is canonical. */
internal data class PreparedAudioSentence(
    val index: Int,
    val text: String,
    val locatorJson: String
)

/** Fixed, non-localized failure codes. Callers map them to UI text; they are safe to log. */
internal object PreparedAudioErrors {
    const val NOT_FOUND = "not_found"
    const val INVALID_VERSION = "invalid_version"
    const val READY_IMMUTABLE = "ready_immutable"
    const val READY_REQUIRES_COMPLETE = "ready_requires_complete"
    const val INDEX_CONFLICT = "index_conflict"
    const val EMPTY_INDEX = "empty_index"
    const val INVALID_SENTENCE = "invalid_sentence"
    const val CONFIGURATION_MISMATCH = "configuration_mismatch"
    const val UNKNOWN_SENTENCE = "unknown_sentence"
    const val INVALID_AUDIO = "invalid_audio"
    const val TOO_LARGE = "too_large"
    const val STORAGE = "storage"
    const val INCOMPLETE = "incomplete"
    const val INDEXING = "indexing"
    const val NOT_READY = "not_ready"
    const val FINGERPRINT_MISMATCH = "fingerprint_mismatch"
    const val MISSING_SOURCE = "missing_source"
}

/** A store failure carrying only a fixed [code]; no book text, path or provider answer. */
internal class PreparedAudioException(val code: String) : Exception(code)

/**
 * Durable, versioned sentence audio that is independent of [SentenceAudioCache]: eviction or a
 * cache clear never touches it. A version pins one book fingerprint, provider, accent and frozen
 * synthesis configuration, owns one private clip directory, and publishes each clip with an atomic
 * rename before its row says the clip exists.
 *
 * Index rows live in a private SQLite database so app startup loads only version metadata; text and
 * locators are read in bounded pages and never all at once. The database transaction is the atomic
 * checkpoint: an interrupted index keeps its committed prefix and becomes a resumable pause, a torn
 * clip is invisible until renamed, and READY is published only after every committed row has a
 * complete validated clip. A [READY] version is immutable.
 *
 * Playback goes through [open], whose lease blocks delete() until close(); the returned resolver
 * only reads pinned files and never falls back to synthesis or [SentenceAudioCache].
 */
internal class PreparedAudioStore(private val directory: File) {

    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<String, AtomicInteger>()
    private var initialized = false
    private var databaseHandle: SQLiteDatabase? = null
    private val versionsById = LinkedHashMap<String, Meta>()

    private val versionsRoot = File(directory, VERSIONS_DIRECTORY)
    private val trashRoot = File(directory, TRASH_DIRECTORY)

    private val mutableVersions = MutableStateFlow<List<PreparedAudioVersion>>(emptyList())
    val versions: StateFlow<List<PreparedAudioVersion>> = mutableVersions.asStateFlow()


    /** Reads metadata, turning interrupted active work into a pause on the first load only. */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    if (initialized) rescanLocked() else initializeLocked()
                }
            }
        }
    }

    /** Creates a new pinned version with a fresh id; an existing ready version is never reused. */
    suspend fun create(
        bookId: Long,
        bookFingerprint: String,
        provider: SentenceSpeechProvider,
        accent: String,
        configuration: String,
        voiceLabel: String
    ): PreparedAudioVersion = withContext(Dispatchers.IO) {
        mutex.withLock {
            storage {
                ensureLoadedLocked()
                if (bookFingerprint.isBlank()) {
                    throw PreparedAudioException(PreparedAudioErrors.INVALID_VERSION)
                }
                val id = UUID.randomUUID().toString()
                ensureDirectory(versionsRoot)
                val preparedDirectory = versionDirectory(id)
                if (!preparedDirectory.mkdirs() && !preparedDirectory.isDirectory) {
                    throw PreparedAudioException(PreparedAudioErrors.STORAGE)
                }
                ensureDirectory(File(preparedDirectory, CLIPS_DIRECTORY))
                syncDirectory(versionsRoot)
                val meta = Meta(
                    id = id,
                    bookId = bookId,
                    bookFingerprint = bookFingerprint,
                    provider = provider,
                    accent = accent,
                    configuration = configuration,
                    voiceLabel = voiceLabel,
                    status = PreparedAudioStatus.INDEXING,
                    completed = 0,
                    total = 0,
                    bytes = 0L,
                    error = null,
                    indexComplete = false,
                    indexedTotal = 0,
                    createdAt = System.currentTimeMillis()
                )
                try {
                    transaction {
                        database().execSQL(
                            "INSERT INTO $VERSIONS_TABLE(" +
                                "id,book_id,book_fingerprint,provider,accent,configuration," +
                                "voice_label,status,completed,total,bytes,error," +
                                "index_complete,indexed_total,created_at" +
                                ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                meta.id,
                                meta.bookId,
                                meta.bookFingerprint,
                                meta.provider.name,
                                meta.accent,
                                meta.configuration,
                                meta.voiceLabel,
                                meta.status.name,
                                meta.completed,
                                meta.total,
                                meta.bytes,
                                meta.error,
                                if (meta.indexComplete) 1 else 0,
                                meta.indexedTotal,
                                meta.createdAt
                            )
                        )
                    }
                } catch (failure: Exception) {
                    preparedDirectory.deleteRecursively()
                    throw failure
                }
                versionsById[id] = meta
                publishLocked()
                meta.version()
            }
        }
    }

    /**
     * Adds one indexed sentence. The operation is idempotent by index while the version is not
     * ready: the same canonical text is a no-op, while different text at that index is refused so
     * two segmentations can never mix. The row and the index-complete invalidation commit together.
     */
    suspend fun addSentence(id: String, sentence: PreparedAudioSentence) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    ensureLoadedLocked()
                    val version = requireVersion(id)
                    if (version.status == PreparedAudioStatus.READY) {
                        throw PreparedAudioException(PreparedAudioErrors.READY_IMMUTABLE)
                    }
                    if (sentence.index < 0) {
                        throw PreparedAudioException(PreparedAudioErrors.INVALID_SENTENCE)
                    }
                    val canonical = canonicalSentence(sentence.text)
                    if (
                        canonical.isBlank() ||
                        canonical.length > MAX_INDEX_TEXT_CHARS ||
                        sentence.locatorJson.length > MAX_LOCATOR_CHARS
                    ) {
                        throw PreparedAudioException(PreparedAudioErrors.INVALID_SENTENCE)
                    }
                    val hash = sentenceHash(canonical)
                    val existingHash = hashAtLocked(id, sentence.index)
                    if (existingHash != null) {
                        // Re-indexing after an interruption repeats the same locators; the
                        // canonical text hash is the identity that must never change at one
                        // index. A differing text is a real conflict, while a re-serialized
                        // locator keeps the first copy.
                        if (existingHash == hash) return@storage Unit
                        throw PreparedAudioException(PreparedAudioErrors.INDEX_CONFLICT)
                    }
                    val hasClip = clipFiles(id, hash).isNotEmpty()
                    transaction {
                        database().execSQL(
                            "INSERT INTO $SENTENCES_TABLE(version_id,idx,text,hash,locator)" +
                                " VALUES(?,?,?,?,?)",
                            arrayOf<Any?>(id, sentence.index, canonical, hash, sentence.locatorJson)
                        )
                        database().execSQL(
                            "UPDATE $VERSIONS_TABLE SET total=total+1," +
                                "completed=completed+?,index_complete=0 WHERE id=?",
                            arrayOf<Any?>(if (hasClip) 1 else 0, id)
                        )
                    }
                    version.total += 1
                    version.indexComplete = false
                    if (hasClip) {
                        version.completed = (version.completed + 1).coerceAtMost(version.total)
                    }
                    publishLocked()
                }
            }
        }
    }

    /** Requires a nonempty index and turns an indexing preparation into a resumable pause. */
    suspend fun finishIndex(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    ensureLoadedLocked()
                    val version = requireVersion(id)
                    if (version.status == PreparedAudioStatus.READY) {
                        throw PreparedAudioException(PreparedAudioErrors.READY_IMMUTABLE)
                    }
                    val total = countSentencesLocked(id)
                    if (total == 0) throw PreparedAudioException(PreparedAudioErrors.EMPTY_INDEX)
                    val counts = computeCountsLocked(id)
                    transaction {
                        database().execSQL(
                            "UPDATE $VERSIONS_TABLE SET status=?,error=NULL,index_complete=1," +
                                "indexed_total=?,total=?,completed=?,bytes=? WHERE id=?",
                            arrayOf<Any?>(
                                PreparedAudioStatus.PAUSED.name,
                                total,
                                counts.total,
                                counts.completed,
                                counts.bytes,
                                id
                            )
                        )
                    }
                    version.status = PreparedAudioStatus.PAUSED
                    version.error = null
                    version.indexComplete = true
                    version.indexedTotal = total
                    version.total = counts.total
                    version.completed = counts.completed
                    version.bytes = counts.bytes
                    publishLocked()
                }
            }
        }
    }


    /**
     * A bounded page of indexed sentences strictly after [afterIndex]. A preparation resumes by
     * walking pages, so a whole-book index never has to be materialized in one list. A non-positive
     * [limit] returns an empty page.
     */
    suspend fun sentences(
        id: String,
        afterIndex: Int,
        limit: Int
    ): List<PreparedAudioSentence> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (limit <= 0) return@withLock emptyList()
            storage {
                ensureLoadedLocked()
                requireVersion(id)
                querySentencesLocked(id, afterIndex = afterIndex, limit = limit)
            }
        }
    }

    /**
     * True only when the sentence is indexed and a complete, decodable clip for its canonical text
     * exists. Duplicate sentences at different locators share that one clip.
     */
    suspend fun contains(id: String, sentence: String): Boolean {
        val file = clipTarget(id, sentence) ?: return false
        return withContext(Dispatchers.IO) { inspectClip(file) != null }
    }

    /**
     * Publishes [audio] as the clip for [sentence]. The source is streamed into a private temporary
     * file, validated, synced and atomically renamed. A nonnull configuration that differs from the
     * version's frozen configuration is refused, so one version can never mix voices. The source
     * file is never deleted or moved.
     */
    suspend fun saveClip(id: String, sentence: String, audio: NarrationAudio) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage { saveClipLocked(id, sentence, audio) }
            }
        }
    }

    /** Sets a non-ready status; READY is reachable only through [complete]. */
    suspend fun setStatus(
        id: String,
        status: PreparedAudioStatus,
        error: String? = null
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    ensureLoadedLocked()
                    if (status == PreparedAudioStatus.READY) {
                        throw PreparedAudioException(PreparedAudioErrors.READY_REQUIRES_COMPLETE)
                    }
                    val version = requireVersion(id)
                    if (version.status == PreparedAudioStatus.READY) {
                        throw PreparedAudioException(PreparedAudioErrors.READY_IMMUTABLE)
                    }
                    if (version.status == status && version.error == error) return@storage Unit
                    transaction {
                        database().execSQL(
                            "UPDATE $VERSIONS_TABLE SET status=?,error=? WHERE id=?",
                            arrayOf<Any?>(status.name, error, id)
                        )
                    }
                    version.status = status
                    version.error = error
                    publishLocked()
                }
            }
        }
    }

    /**
     * Validates every committed index row's complete matching clip and only then publishes READY.
     * The row count must still equal the count recorded when the iterator reached EOF, so a lost or
     * damaged row can never leave a partially playable version.
     */
    suspend fun complete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    ensureLoadedLocked()
                    val version = requireVersion(id)
                    if (version.status == PreparedAudioStatus.INDEXING) {
                        throw PreparedAudioException(PreparedAudioErrors.INDEXING)
                    }
                    val total = countSentencesLocked(id)
                    if (total == 0) throw PreparedAudioException(PreparedAudioErrors.EMPTY_INDEX)
                    if (!version.indexComplete || version.indexedTotal != total) {
                        throw PreparedAudioException(PreparedAudioErrors.INDEXING)
                    }
                    var totalBytes = 0L
                    database().rawQuery(
                        "SELECT DISTINCT hash FROM $SENTENCES_TABLE WHERE version_id=?",
                        arrayOf(id)
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val hash = cursor.getString(0)
                                ?: throw PreparedAudioException(PreparedAudioErrors.INCOMPLETE)
                            if (!HASH_PATTERN.matches(hash)) {
                                throw PreparedAudioException(PreparedAudioErrors.INCOMPLETE)
                            }
                            val file = clipFiles(id, hash).firstOrNull()
                                ?: throw PreparedAudioException(PreparedAudioErrors.INCOMPLETE)
                            val clip = inspectClip(file)
                                ?: throw PreparedAudioException(PreparedAudioErrors.INCOMPLETE)
                            totalBytes += clip.bytes
                        }
                    }
                    if (version.status == PreparedAudioStatus.READY) return@storage Unit
                    transaction {
                        database().execSQL(
                            "UPDATE $VERSIONS_TABLE SET status=?,error=NULL,completed=?,bytes=?," +
                                "index_complete=1,indexed_total=? WHERE id=?",
                            arrayOf<Any?>(
                                PreparedAudioStatus.READY.name,
                                total,
                                totalBytes,
                                total,
                                id
                            )
                        )
                    }
                    version.status = PreparedAudioStatus.READY
                    version.error = null
                    version.total = total
                    version.completed = total
                    version.bytes = totalBytes
                    version.indexComplete = true
                    version.indexedTotal = total
                    publishLocked()
                }
            }
        }
    }

    /**
     * Returns a source-free resolver for a READY version whose book fingerprint matches. The lease
     * keeps [delete] from removing the files until the resolver is closed.
     */
    suspend fun open(id: String, bookFingerprint: String): NarrationAudioResolver {
        var acquired: NarrationAudioResolver? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    storage {
                        ensureLoadedLocked()
                        val version = requireVersion(id)
                        if (version.status != PreparedAudioStatus.READY) {
                            throw PreparedAudioException(PreparedAudioErrors.NOT_READY)
                        }
                        if (
                            bookFingerprint.isBlank() ||
                            !version.bookFingerprint.equals(bookFingerprint, ignoreCase = true)
                        ) {
                            throw PreparedAudioException(PreparedAudioErrors.FINGERPRINT_MISMATCH)
                        }
                        acquireLease(id)
                        SavedResolver(id, bookFingerprint).also { acquired = it }
                    }
                }
            }
        } catch (failure: Throwable) {
            // Prompt cancellation can discard the IO result before the caller receives its lease.
            acquired?.close()
            throw failure
        }
    }

    /**
     * Deletes a version and all of its clips. It refuses a leased, indexing or preparing version, so
     * an active producer must be cancelled and joined before the directory can disappear; a write
     * that somehow outlives that join finds no database row and cannot recreate it.
     */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            storage {
                ensureLoadedLocked()
                if (!ID_PATTERN.matches(id)) return@storage false
                val version = versionsById[id] ?: return@storage false
                if (
                    version.status == PreparedAudioStatus.INDEXING ||
                    version.status == PreparedAudioStatus.PREPARING
                ) {
                    return@storage false
                }
                if ((leases[id]?.get() ?: 0) > 0) return@storage false
                transaction {
                    database().execSQL(
                        "DELETE FROM $SENTENCES_TABLE WHERE version_id=?",
                        arrayOf<Any?>(id)
                    )
                    database().execSQL(
                        "DELETE FROM $VERSIONS_TABLE WHERE id=?",
                        arrayOf<Any?>(id)
                    )
                }
                versionsById.remove(id)
                publishLocked()
                // Metadata is gone, so no stale producer can resurrect this version. Files are
                // cleaned best-effort; a leftover directory is removed on the next refresh.
                val directoryToDelete = versionDirectory(id)
                if (directoryToDelete.exists()) {
                    val trash = File(trashRoot, "trash-$id-${UUID.randomUUID()}")
                    runCatching {
                        ensureDirectory(trashRoot)
                        moveAtomically(directoryToDelete, trash)
                        trash.deleteRecursively()
                    }.onFailure {
                        runCatching { directoryToDelete.deleteRecursively() }
                    }
                }
                true
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata and database
    // ---------------------------------------------------------------------------------------------

    private class Meta(
        val id: String,
        val bookId: Long,
        val bookFingerprint: String,
        val provider: SentenceSpeechProvider,
        val accent: String,
        val configuration: String,
        val voiceLabel: String,
        var status: PreparedAudioStatus,
        var completed: Int,
        var total: Int,
        var bytes: Long,
        var error: String?,
        var indexComplete: Boolean,
        var indexedTotal: Int,
        val createdAt: Long
    ) {
        fun version() = PreparedAudioVersion(
            id = id,
            bookId = bookId,
            bookFingerprint = bookFingerprint,
            provider = provider,
            accent = accent,
            configuration = configuration,
            voiceLabel = voiceLabel,
            status = status,
            completed = completed,
            total = total,
            bytes = bytes,
            error = error
        )
    }

    private data class Counts(val total: Int, val completed: Int, val bytes: Long)

    private data class Clip(
        val file: File,
        val format: NarrationAudioFormat,
        val durationMs: Long,
        val bytes: Long
    )

    private class PlaybackTarget(val file: File, val configuration: String)

    private fun database(): SQLiteDatabase {
        databaseHandle?.takeIf { it.isOpen }?.let { return it }
        ensureDirectory(directory)
        val opened = try {
            SQLiteDatabase.openOrCreateDatabase(File(directory, DATABASE_FILE), null)
        } catch (_: Exception) {
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
        try {
            runCatching { opened.enableWriteAheadLogging() }
            runCatching { opened.setForeignKeyConstraintsEnabled(true) }
            opened.execSQL(CREATE_VERSIONS_TABLE)
            opened.execSQL(CREATE_SENTENCES_TABLE)
            opened.execSQL(CREATE_SENTENCE_HASH_INDEX)
        } catch (_: Exception) {
            runCatching { opened.close() }
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
        databaseHandle = opened
        return opened
    }

    private inline fun <T> transaction(block: () -> T): T {
        val database = database()
        database.beginTransaction()
        return try {
            val value = block()
            database.setTransactionSuccessful()
            value
        } finally {
            database.endTransaction()
        }
    }

    /** Turns any SQLite/file failure into the one fixed storage code, never a raw error. */
    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (failure: PreparedAudioException) {
        throw failure
    } catch (_: Exception) {
        throw PreparedAudioException(PreparedAudioErrors.STORAGE)
    }

    private fun loadVersionsLocked(): LinkedHashMap<String, Meta> {
        val result = LinkedHashMap<String, Meta>()
        database().rawQuery(
            "SELECT id,book_id,book_fingerprint,provider,accent,configuration,voice_label," +
                "status,completed,total,bytes,error,index_complete,indexed_total,created_at" +
                " FROM $VERSIONS_TABLE ORDER BY created_at DESC,id",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val pair = readMeta(cursor) ?: continue
                result[pair.first] = pair.second
            }
        }
        return result
    }

    private fun readMeta(cursor: Cursor): Pair<String, Meta>? {
        val id = cursor.getString(0) ?: return null
        if (!ID_PATTERN.matches(id)) return null
        val providerName = cursor.getString(3) ?: return null
        val provider = SentenceSpeechProvider.entries.firstOrNull { it.name == providerName }
            ?: return null
        val statusName = cursor.getString(7) ?: return null
        val status = PreparedAudioStatus.entries.firstOrNull { it.name == statusName } ?: return null
        val total = cursor.getLong(9).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val completed = cursor.getLong(8)
            .coerceAtLeast(0L)
            .coerceAtMost(total.toLong())
            .toInt()
        val meta = Meta(
            id = id,
            bookId = cursor.getLong(1),
            bookFingerprint = cursor.getString(2) ?: return null,
            provider = provider,
            accent = cursor.getString(4) ?: "",
            configuration = cursor.getString(5) ?: "",
            voiceLabel = cursor.getString(6) ?: "",
            status = status,
            completed = completed,
            total = total,
            bytes = cursor.getLong(10).coerceAtLeast(0L),
            error = cursor.getString(11),
            indexComplete = cursor.getLong(12) != 0L ||
                status == PreparedAudioStatus.READY,
            indexedTotal = cursor.getLong(13)
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            createdAt = cursor.getLong(14).coerceAtLeast(0L)
        )
        return id to meta
    }

    private fun initializeLocked() {
        if (initialized) return
        ensureDirectory(directory)
        ensureDirectory(versionsRoot)
        cleanupTrashLocked()
        val loaded = storage { loadVersionsLocked() }
        for (version in loaded.values) {
            // A process death during indexing or preparation leaves a resumable pause. READY is
            // immutable, so it is never rewritten here. Counts stay as committed; finishIndex and
            // complete recompute them exactly when a run reaches those checkpoints, so startup
            // never scans sentence rows or clip files for the whole library.
            if (
                version.status == PreparedAudioStatus.INDEXING ||
                version.status == PreparedAudioStatus.PREPARING
            ) {
                storage {
                    transaction {
                        database().execSQL(
                            "UPDATE $VERSIONS_TABLE SET status=? WHERE id=?",
                            arrayOf<Any?>(PreparedAudioStatus.PAUSED.name, version.id)
                        )
                    }
                    version.status = PreparedAudioStatus.PAUSED
                }
            }
        }
        versionsById.clear()
        versionsById.putAll(loaded)
        cleanupOrphanVersionDirectories(loaded.keys)
        initialized = true
        publishLocked()
    }

    private fun rescanLocked() {
        storage {
            ensureDirectory(versionsRoot)
            cleanupTrashLocked()
            val loaded = loadVersionsLocked()
            versionsById.clear()
            versionsById.putAll(loaded)
            cleanupOrphanVersionDirectories(loaded.keys)
            publishLocked()
        }
    }

    private fun querySentencesLocked(
        id: String,
        afterIndex: Int,
        limit: Int
    ): List<PreparedAudioSentence> {
        val sql = "SELECT idx,text,locator FROM $SENTENCES_TABLE " +
            "WHERE version_id=? AND idx>? ORDER BY idx LIMIT ?"
        return database().rawQuery(sql, arrayOf(id, afterIndex.toString(), limit.toString())).use { cursor ->
            val page = ArrayList<PreparedAudioSentence>(limit.coerceAtMost(1024))
            while (cursor.moveToNext()) {
                page += PreparedAudioSentence(
                    index = cursor.getLong(0).toInt(),
                    text = cursor.getString(1) ?: continue,
                    locatorJson = cursor.getString(2) ?: ""
                )
            }
            page
        }
    }

    private fun hashAtLocked(id: String, index: Int): String? =
        database().rawQuery(
            "SELECT hash FROM $SENTENCES_TABLE WHERE version_id=? AND idx=?",
            arrayOf(id, index.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun hasHashLocked(id: String, hash: String): Boolean =
        database().rawQuery(
            "SELECT 1 FROM $SENTENCES_TABLE WHERE version_id=? AND hash=? LIMIT 1",
            arrayOf(id, hash)
        ).use { it.moveToFirst() }

    private fun countRowsForHashLocked(id: String, hash: String): Int =
        database().rawQuery(
            "SELECT COUNT(*) FROM $SENTENCES_TABLE WHERE version_id=? AND hash=?",
            arrayOf(id, hash)
        ).use {
            if (it.moveToFirst()) {
                it.getLong(0).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            }
        }

    private fun countSentencesLocked(id: String): Int =
        database().rawQuery(
            "SELECT COUNT(*) FROM $SENTENCES_TABLE WHERE version_id=?",
            arrayOf(id)
        ).use {
            if (it.moveToFirst()) {
                it.getLong(0).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            }
        }

    /**
     * Recounts rows and published bytes by streaming distinct sentence hashes; memory stays at one
     * hash per row group, never the whole text/locator index.
     */
    private fun computeCountsLocked(id: String): Counts {
        var completed = 0
        var bytes = 0L
        database().rawQuery(
            "SELECT hash,COUNT(*) FROM $SENTENCES_TABLE WHERE version_id=? GROUP BY hash",
            arrayOf(id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val hash = cursor.getString(0) ?: continue
                if (!HASH_PATTERN.matches(hash)) continue
                val rows = cursor.getLong(1)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val files = clipFiles(id, hash)
                if (files.isEmpty()) continue
                completed += rows
                bytes += files.sumOf { it.length() }
            }
        }
        val total = countSentencesLocked(id)
        return Counts(total, completed.coerceAtMost(total), bytes)
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    private suspend fun clipTarget(id: String, sentence: String): File? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    ensureLoadedLocked()
                    if (versionsById[id] == null) return@storage null
                    val canonical = canonicalSentence(sentence)
                    if (canonical.isBlank()) return@storage null
                    val hash = sentenceHash(canonical)
                    if (!hasHashLocked(id, hash)) return@storage null
                    clipFiles(id, hash).firstOrNull()
                }
            }
        }

    private suspend fun playbackTarget(
        id: String,
        sentence: String,
        fingerprint: String
    ): PlaybackTarget? = withContext(Dispatchers.IO) {
        mutex.withLock {
            storage {
                val version = versionsById[id] ?: return@storage null
                if (version.status != PreparedAudioStatus.READY) return@storage null
                if (!version.bookFingerprint.equals(fingerprint, ignoreCase = true)) {
                    return@storage null
                }
                val canonical = canonicalSentence(sentence)
                if (canonical.isBlank()) return@storage null
                val hash = sentenceHash(canonical)
                if (!hasHashLocked(id, hash)) return@storage null
                val file = clipFiles(id, hash).firstOrNull() ?: return@storage null
                PlaybackTarget(file, version.configuration)
            }
        }
    }

    private inner class SavedResolver(
        private val versionId: String,
        private val fingerprint: String
    ) : NarrationAudioResolver {
        private val closed = AtomicBoolean(false)

        override suspend fun configuration(): String = withContext(Dispatchers.IO) {
            mutex.withLock {
                storage {
                    versionsById[versionId]?.configuration
                        ?: throw PreparedAudioException(PreparedAudioErrors.NOT_FOUND)
                }
            }
        }

        override suspend fun audioFor(sentence: String): Result<NarrationAudio> {
            val startedAt = System.nanoTime()
            if (closed.get()) return Result.failure(NarrationFailure.Unavailable())
            val target = playbackTarget(versionId, sentence, fingerprint)
                ?: return Result.failure(NarrationFailure.Storage())
            val clip = withContext(Dispatchers.IO) { inspectClip(target.file) }
                ?: return Result.failure(NarrationFailure.InvalidAudio())
            if (closed.get()) return Result.failure(NarrationFailure.Unavailable())
            return Result.success(
                NarrationAudio(
                    file = target.file,
                    fromCache = true,
                    durationMs = clip.durationMs,
                    configuration = target.configuration,
                    diagnosticIdentity = "source=saved version=${versionId.take(8)} " +
                        "resolveMs=${(System.nanoTime() - startedAt) / 1_000_000L}"
                )
            )
        }

        override fun cancelPending() {
            // Nothing is ever synthesized or queued here; playback reads pinned files only.
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseLease(versionId)
        }
    }

    /**
     * Acquisition and release are atomic per id. A close that reaches zero cannot remove a lease
     * that another open acquired in between, and delete() is serialized with open() by [mutex].
     */
    private fun acquireLease(id: String) {
        leases.compute(id) { _, counter ->
            (counter ?: AtomicInteger(0)).apply { incrementAndGet() }
        }
    }

    private fun releaseLease(id: String) {
        leases.computeIfPresent(id) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Clips and publishing
    // ---------------------------------------------------------------------------------------------

    private fun saveClipLocked(id: String, sentence: String, audio: NarrationAudio) {
        ensureLoadedLocked()
        val version = requireVersion(id)
        if (version.status == PreparedAudioStatus.READY) {
            throw PreparedAudioException(PreparedAudioErrors.READY_IMMUTABLE)
        }
        val audioConfiguration = audio.configuration
        if (audioConfiguration != null && audioConfiguration != version.configuration) {
            throw PreparedAudioException(PreparedAudioErrors.CONFIGURATION_MISMATCH)
        }
        val canonical = canonicalSentence(sentence)
        if (canonical.isBlank()) throw PreparedAudioException(PreparedAudioErrors.INVALID_SENTENCE)
        val hash = sentenceHash(canonical)
        if (!hasHashLocked(id, hash)) {
            throw PreparedAudioException(PreparedAudioErrors.UNKNOWN_SENTENCE)
        }
        val source = audio.file
        if (!source.isFile) throw PreparedAudioException(PreparedAudioErrors.INVALID_AUDIO)
        val sourceLength = source.length()
        if (sourceLength <= 0L) throw PreparedAudioException(PreparedAudioErrors.INVALID_AUDIO)
        if (sourceLength > NARRATION_MAX_SENTENCE_AUDIO_BYTES) {
            throw PreparedAudioException(PreparedAudioErrors.TOO_LARGE)
        }
        val format = detectedFormat(source)

        val clips = clipsDirectory(id)
        ensureDirectory(clips)
        val extension = format.extension
        val temporary = File(clips, "$hash.$extension$TEMP_SUFFIX")
        val target = File(clips, "$hash.$extension")
        if (source.absoluteFile == target.absoluteFile) return
        ensureClipSpace(clips, sourceLength)

        val previousFiles = clipFiles(id, hash)
        val previousBytes = previousFiles.sumOf { it.length() }
        val hadClip = previousFiles.isNotEmpty()
        val rows = countRowsForHashLocked(id, hash)

        copyStreaming(source, temporary)
        val copied = inspectClip(temporary)
        if (copied == null || copied.format != format) {
            temporary.delete()
            throw PreparedAudioException(PreparedAudioErrors.INVALID_AUDIO)
        }
        try {
            moveAtomically(temporary, target)
        } catch (_: Exception) {
            temporary.delete()
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
        syncDirectory(clips)
        previousFiles
            .filter { it.absoluteFile != target.absoluteFile }
            .filter { it.absoluteFile != source.absoluteFile }
            .forEach { it.delete() }

        val newCompleted = if (hadClip) {
            version.completed
        } else {
            (version.completed + rows).coerceAtMost(version.total)
        }
        val newBytes = (version.bytes + target.length() - previousBytes).coerceAtLeast(0L)
        transaction {
            database().execSQL(
                "UPDATE $VERSIONS_TABLE SET completed=?,bytes=? WHERE id=?",
                arrayOf<Any?>(newCompleted, newBytes, id)
            )
        }
        version.completed = newCompleted
        version.bytes = newBytes
        publishLocked()
    }

    private fun versionDirectory(id: String): File = File(versionsRoot, id)

    private fun clipsDirectory(id: String): File =
        File(versionDirectory(id), CLIPS_DIRECTORY)

    private fun clipFiles(id: String, hash: String): List<File> {
        val clips = clipsDirectory(id)
        return listOf(
            File(clips, "$hash.${NarrationAudioFormat.Mp3.extension}"),
            File(clips, "$hash.${NarrationAudioFormat.Wave.extension}")
        ).filter { it.isFile }
    }

    private fun requireVersion(id: String): Meta {
        if (!ID_PATTERN.matches(id)) throw PreparedAudioException(PreparedAudioErrors.NOT_FOUND)
        return versionsById[id] ?: throw PreparedAudioException(PreparedAudioErrors.NOT_FOUND)
    }

    private fun ensureLoadedLocked() {
        if (!initialized) initializeLocked()
    }

    private fun publishLocked() {
        mutableVersions.value = versionsById.values
            .sortedWith(compareByDescending<Meta> { it.createdAt }.thenBy { it.id })
            .map { it.version() }
    }

    private fun cleanupTrashLocked() {
        trashRoot.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * A committed deletion can be followed by process death before the files are removed. Only
     * directories with no database row are orphans. Keep rows with unreadable metadata so recovery
     * never mistakes an unsupported version for an explicit deletion.
     */
    private fun cleanupOrphanVersionDirectories(knownIds: Set<String>) {
        versionsRoot.listFiles { file -> file.isDirectory && ID_PATTERN.matches(file.name) }
            ?.forEach { versionDirectory ->
                if (versionDirectory.name in knownIds) return@forEach
                val hasRow = database().rawQuery(
                    "SELECT 1 FROM $VERSIONS_TABLE WHERE id=?",
                    arrayOf(versionDirectory.name)
                ).use { it.moveToFirst() }
                if (!hasRow) runCatching { versionDirectory.deleteRecursively() }
            }
    }

    // ---------------------------------------------------------------------------------------------
    // Small file primitives
    // ---------------------------------------------------------------------------------------------

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
    }

    /** A container guess from the header only; the copied temporary is fully validated after. */
    private fun detectedFormat(file: File): NarrationAudioFormat =
        if (isWave(file)) NarrationAudioFormat.Wave else NarrationAudioFormat.Mp3

    /**
     * Refuses a clip write before copying when the volume cannot hold the clip plus working
     * headroom. A concurrent writer or an app quota can still exhaust space later; copy and
     * metadata failures then surface as the same fixed storage code.
     */
    private fun ensureClipSpace(directory: File, needed: Long) {
        val usable = runCatching { directory.usableSpace }.getOrNull() ?: return
        if (usable < needed + CLIP_HEADROOM_BYTES) {
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
    }

    private fun copyStreaming(source: File, temporary: File) {
        try {
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                }
                output.flush()
                output.fd.sync()
            }
        } catch (_: Exception) {
            temporary.delete()
            throw PreparedAudioException(PreparedAudioErrors.STORAGE)
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Best-effort directory durability; an unsupported filesystem keeps the rename itself. */
    private fun syncDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /**
     * A complete clip with its real duration. A WAV must pass the full RIFF/PCM walk; anything else
     * must be a complete MPEG frame sequence. Damaged files are never treated as shorter sentences.
     */
    private fun inspectClip(file: File): Clip? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > NARRATION_MAX_SENTENCE_AUDIO_BYTES) return null
        return if (isWave(file)) {
            WaveAudio.durationMs(file)
                ?.takeIf { it > 0L }
                ?.let { Clip(file, NarrationAudioFormat.Wave, it, length) }
        } else {
            val bytes = try {
                file.readBytes()
            } catch (_: Exception) {
                return null
            }
            if (bytes.size.toLong() > NARRATION_MAX_SENTENCE_AUDIO_BYTES) return null
            Mp3Audio.durationMs(bytes)
                ?.takeIf { it > 0L }
                ?.let { Clip(file, NarrationAudioFormat.Mp3, it, bytes.size.toLong()) }
        }
    }

    private fun isWave(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12L) {
                false
            } else {
                val header = ByteArray(12)
                input.readFully(header)
                header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() &&
                    header[11] == 'E'.code.toByte()
            }
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DIRECTORY_NAME = "prepared-audio"

        /** App-private storage outside the sentence cache, so clearing the cache never removes it. */
        fun directory(noBackupFiles: File): File = File(noBackupFiles, DIRECTORY_NAME)

        /** Streams [file] into SHA-256; no byte array proportional to the book is allocated. */
        suspend fun fingerprint(file: File): String = withContext(Dispatchers.IO) {
            if (!file.isFile) throw PreparedAudioException(PreparedAudioErrors.MISSING_SOURCE)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                hex(digest.digest())
            } catch (_: Exception) {
                throw PreparedAudioException(PreparedAudioErrors.STORAGE)
            }
        }

        private const val DATABASE_FILE = "index.db"
        private const val VERSIONS_TABLE = "versions"
        private const val SENTENCES_TABLE = "sentences"
        private const val CREATE_VERSIONS_TABLE =
            "CREATE TABLE IF NOT EXISTS versions(" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "book_id INTEGER NOT NULL," +
                "book_fingerprint TEXT NOT NULL," +
                "provider TEXT NOT NULL," +
                "accent TEXT NOT NULL," +
                "configuration TEXT NOT NULL," +
                "voice_label TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "completed INTEGER NOT NULL," +
                "total INTEGER NOT NULL," +
                "bytes INTEGER NOT NULL," +
                "error TEXT," +
                "index_complete INTEGER NOT NULL DEFAULT 0," +
                "indexed_total INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)"
        private const val CREATE_SENTENCES_TABLE =
            "CREATE TABLE IF NOT EXISTS sentences(" +
                "version_id TEXT NOT NULL," +
                "idx INTEGER NOT NULL," +
                "text TEXT NOT NULL," +
                "hash TEXT NOT NULL," +
                "locator TEXT NOT NULL," +
                "PRIMARY KEY(version_id,idx)," +
                "FOREIGN KEY(version_id) REFERENCES versions(id) ON DELETE CASCADE)"
        private const val CREATE_SENTENCE_HASH_INDEX =
            "CREATE INDEX IF NOT EXISTS sentences_version_hash" +
                " ON sentences(version_id,hash)"
    }
}

private const val VERSIONS_DIRECTORY = "versions"
private const val TRASH_DIRECTORY = "trash"
private const val CLIPS_DIRECTORY = "clips"
private const val TEMP_SUFFIX = ".tmp"
private const val MAX_INDEX_TEXT_CHARS = 65_536
private const val MAX_LOCATOR_CHARS = 1_000_000
private const val CLIP_HEADROOM_BYTES = 32L * 1024 * 1024
private const val READ_BUFFER_BYTES = 64 * 1024

private val ID_PATTERN =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val HASH_PATTERN = Regex("[0-9a-f]{64}")

private fun sentenceHash(canonical: String): String =
    hex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))

private fun hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(digits[value ushr 4])
            append(digits[value and 0x0F])
        }
    }
}
