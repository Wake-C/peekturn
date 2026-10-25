package app.peekturn.reader.video

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The private caption budget is shared by every video. Entries are evicted only when it is
 * exceeded, least recently used first; a video's row, its position and its statistics never live
 * here and are therefore never touched by eviction.
 */
internal const val VIDEO_CAPTION_CACHE_BYTES = 128L * 1024 * 1024

/** A single record above this size is not cached; caption responses are already bounded by source. */
internal const val VIDEO_CAPTION_MAX_ENTRY_BYTES = 24L * 1024 * 1024

internal const val VIDEO_CAPTIONS_FORMAT = "video-captions-v2"
internal const val VIDEO_CAPTION_GROUPS_FORMAT = "video-caption-groups-v1"

/** The parser's own ceiling; a persisted file larger than this is corrupt, not a caption track. */
private const val MAX_CUES = 100_000

private const val ORIGINAL_PREFIX = "original-"
private const val MERGED_PREFIX = "merged-"

private val sourceHashPattern = Regex("[0-9a-fA-F]{64}")
private val originalKeys = setOf("format", "videoId", "trackId", "language", "automatic", "sourceHash", "cues")
private val cueKeys = setOf("id", "startMs", "endMs", "text")
private val wordKeys = setOf("text", "startMs", "exact")
private val groupKeys = setOf("format", "videoId", "trackId", "sourceHash", "mergeKeyHash", "groups")

/**
 * App-private persistence for parsed captions and validated merge partitions.
 *
 * Nothing here is a source of truth for a library entry: [clear] removes files only, so a cleared
 * cache leaves the video, its position and its learning time intact and a real re-fetch is the only
 * way back. Each video has an in-memory generation, advanced by [clear] before any file is touched.
 * A producer that finishes afterwards presents the generation it started with and is refused, so an
 * in-flight task can never rebuild a cleared cache.
 */
class VideoCaptionCache(
    private val directory: File,
    private val maxBytes: Long = VIDEO_CAPTION_CACHE_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    /** Lazily counted, so the directory is walked only when the budget is checked. */
    private var knownBytes: Long? = null

    init {
        require(maxBytes > 0) { "The caption budget must be positive" }
        directory.mkdirs()
    }

    fun epoch(videoId: String): Long = generations[videoId]?.get() ?: 0L

    /** A parsed caption track, keyed by the stable track identity, or null for a miss or corruption. */
    suspend fun original(videoId: String, trackId: String): CaptionDocument? = withContext(io) {
        mutex.withLock {
            val file = originalFile(videoId, trackId)
            readEntry(file) { bytes -> decodeOriginal(bytes, videoId, trackId) }
        }
    }

    /**
     * Persists a complete parsed track. [expectedEpoch] is the generation the producer observed
     * before it started; a clear that happened meanwhile wins and this older result is dropped.
     */
    suspend fun putOriginal(document: CaptionDocument, expectedEpoch: Long): Unit = withContext(io) {
        if (document.sourceHash.isBlank()) return@withContext
        val bytes = encodeOriginal(document).toByteArray(Charsets.UTF_8)
        mutex.withLock {
            writeLocked(document.videoId, originalFile(document.videoId, document.trackId), bytes, expectedEpoch)
        }
    }

    /**
     * Merge partitions for one document and one model identity. A hit is still verified against the
     * caller's document; the model-switch identity itself is owned by `captionMergeIdentity`.
     */
    suspend fun groups(document: CaptionDocument, mergeKey: String): List<CaptionGroup>? = withContext(io) {
        if (mergeKey.isBlank() || document.sourceHash.isBlank()) return@withContext null
        mutex.withLock {
            val file = mergedFile(document, mergeKey)
            readEntry(file) { bytes -> decodeGroups(bytes, document, digest(mergeKey)) }
        }
    }

    /** Existing formatting survives a global model change without being relabeled as that model. */
    internal suspend fun latestMerged(document: CaptionDocument): List<CaptionCue>? = withContext(io) {
        mutex.withLock {
            val prefix = mergedPrefix(document)
            val files = videoDirectory(document.videoId).listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            for (file in files) {
                val keyHash = file.name.substring(prefix.length, file.name.length - 5)
                if (!sourceHashPattern.matches(keyHash)) continue
                val cues = readEntry(file) { bytes ->
                    decodeGroups(bytes, document, keyHash)?.let { applyCaptionGroups(document, it) }
                }
                if (cues != null) return@withLock cues
            }
            null
        }
    }

    suspend fun putGroups(
        document: CaptionDocument,
        mergeKey: String,
        groups: List<CaptionGroup>,
        expectedEpoch: Long
    ): Unit = withContext(io) {
        require(mergeKey.isNotBlank()) { "A merge identity is required" }
        if (document.sourceHash.isBlank()) return@withContext
        val bytes = encodeGroups(document, mergeKey, groups).toByteArray(Charsets.UTF_8)
        mutex.withLock {
            writeLocked(document.videoId, mergedFile(document, mergeKey), bytes, expectedEpoch)
        }
    }

    /**
     * Removes this video's captions. [mergedOnly] keeps the parsed track, so turning merging off or
     * regenerating a model partition never costs a source request. Either clear advances the
     * generation first, which invalidates every producer already running for this video.
     */
    suspend fun clear(videoId: String, mergedOnly: Boolean) {
        generations.computeIfAbsent(videoId) { AtomicLong() }.incrementAndGet()
        withContext(io) {
            mutex.withLock {
                val videoDirectory = videoDirectory(videoId)
                val entries = videoDirectory.listFiles()
                if (entries == null && videoDirectory.exists()) {
                    throw java.io.IOException("Subtitle cache could not be read for clearing")
                }
                val files = entries?.filter {
                    it.isFile && (!mergedOnly || it.name.startsWith(MERGED_PREFIX))
                }.orEmpty()
                var removed = 0L
                var failed = false
                for (file in files) {
                    val length = file.length()
                    if (file.delete()) removed += length
                    else if (file.exists()) failed = true
                }
                subtract(removed)
                if (failed) throw java.io.IOException("Subtitle cache could not be cleared")
                if (!mergedOnly) videoDirectory.delete()
            }
        }
    }

    suspend fun sizeBytes(videoId: String, mergedOnly: Boolean = false): Long = withContext(io) {
        mutex.withLock {
            videoDirectory(videoId).listFiles()
                ?.filter { it.isFile && (!mergedOnly || it.name.startsWith(MERGED_PREFIX)) }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    private inline fun <T> readEntry(file: File, decode: (ByteArray) -> T?): T? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > maxBytes || length > VIDEO_CAPTION_MAX_ENTRY_BYTES) {
            if (file.delete()) subtract(length)
            return null
        }
        // Damage is a miss, never an empty success; the next open fetches the track for real.
        val decoded = runCatching { decode(file.readBytes()) }.getOrNull()
        if (decoded == null) {
            if (file.delete()) subtract(length)
            return null
        }
        file.setLastModified(now())
        return decoded
    }

    private fun writeLocked(videoId: String, target: File, bytes: ByteArray, expectedEpoch: Long): Boolean {
        if (expectedEpoch != epoch(videoId)) return false
        if (bytes.isEmpty() || bytes.size.toLong() > VIDEO_CAPTION_MAX_ENTRY_BYTES || bytes.size.toLong() > maxBytes) return false
        val parent = target.parentFile ?: return false
        parent.mkdirs()
        val previousLength = if (target.isFile) target.length() else 0L
        val totalBefore = totalBytes()
        val temporary = File.createTempFile("write-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            // A clear may have advanced while the bytes were being flushed.
            if (expectedEpoch != epoch(videoId)) {
                temporary.delete()
                return false
            }
            moveAtomically(temporary, target)
        } catch (_: Exception) {
            temporary.delete()
            return false
        }
        target.setLastModified(now())
        knownBytes = (totalBefore - previousLength + bytes.size.toLong()).coerceAtLeast(0L)
        evict(protected = target)
        return true
    }

    private fun totalBytes(): Long = knownBytes ?: cachedEntries().sumOf { it.length() }.also { knownBytes = it }

    private fun subtract(bytes: Long) {
        knownBytes = knownBytes?.let { (it - bytes).coerceAtLeast(0L) }
    }

    private fun cachedEntries(): List<File> = directory.listFiles()
        ?.flatMap { videoDirectory -> videoDirectory.listFiles()?.toList().orEmpty() }
        ?.filter { it.isFile }
        .orEmpty()

    /** Oldest access first, with the path breaking timestamp ties; the new entry is never evicted. */
    private fun evict(protected: File) {
        var total = totalBytes()
        if (total <= maxBytes) return
        val target = maxBytes - maxBytes / 20
        for (file in cachedEntries().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path })) {
            if (total <= target) break
            if (file == protected) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
        knownBytes = total
    }

    private fun moveAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun videoDirectory(videoId: String) = File(directory, digest(videoId))

    private fun originalFile(videoId: String, trackId: String) =
        File(videoDirectory(videoId), "$ORIGINAL_PREFIX${digest(trackId)}.json")

    /** Track/source identity is indexable without reading or deleting another track's cache. */
    private fun mergedPrefix(document: CaptionDocument) =
        "$MERGED_PREFIX${digest(CAPTION_MERGE_PROMPT_VERSION + "\n" + document.trackId + "\n" + document.sourceHash)}-"

    private fun mergedFile(document: CaptionDocument, mergeKey: String) =
        File(videoDirectory(document.videoId), "${mergedPrefix(document)}${digest(mergeKey)}.json")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encodeOriginal(document: CaptionDocument): String {
        val cues = JsonArray().apply {
            document.cues.forEach { cue ->
                add(JsonObject().apply {
                    addProperty("id", cue.id)
                    addProperty("startMs", cue.startMs)
                    addProperty("endMs", cue.endMs)
                    addProperty("text", cue.text)
                    cue.words?.let { words ->
                        add("words", JsonArray().apply {
                            words.forEach { word -> add(JsonObject().apply {
                                addProperty("text", word.text)
                                addProperty("startMs", word.startMs)
                                addProperty("exact", word.exact)
                            }) }
                        })
                    }
                })
            }
        }
        return JsonObject().apply {
            addProperty("format", VIDEO_CAPTIONS_FORMAT)
            addProperty("videoId", document.videoId)
            addProperty("trackId", document.trackId)
            addProperty("language", document.language)
            addProperty("automatic", document.automatic)
            addProperty("sourceHash", document.sourceHash)
            add("cues", cues)
        }.toString()
    }

    private fun encodeGroups(document: CaptionDocument, mergeKey: String, groups: List<CaptionGroup>): String {
        val encoded = JsonArray().apply {
            groups.forEach { group ->
                add(JsonArray().apply {
                    add(group.firstCueId)
                    add(group.lastCueId)
                })
            }
        }
        return JsonObject().apply {
            addProperty("format", VIDEO_CAPTION_GROUPS_FORMAT)
            addProperty("videoId", document.videoId)
            addProperty("trackId", document.trackId)
            addProperty("sourceHash", document.sourceHash)
            addProperty("mergeKeyHash", digest(mergeKey))
            add("groups", encoded)
        }.toString()
    }

    /**
     * Structural decoding only: the record must describe the requested track and a real, ordered
     * cue sequence. Whether a partition is an acceptable merge is decided by the shared grouping
     * rules, not duplicated here.
     */
    private fun decodeOriginal(bytes: ByteArray, videoId: String, trackId: String): CaptionDocument? {
        val root = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: return null
        if (root.keySet() != originalKeys) return null
        val format = root.string("format")
        // Legacy captions remain readable offline, with approximate anchors for in-cue splits.
        if (format != VIDEO_CAPTIONS_FORMAT && format != "video-captions-v1") return null
        if (root.string("videoId") != videoId) return null
        if (root.string("trackId") != trackId) return null
        val language = root.string("language")?.takeIf { it.isNotBlank() } ?: return null
        val automatic = root.boolean("automatic") ?: return null
        val sourceHash = root.string("sourceHash")?.takeIf { sourceHashPattern.matches(it) } ?: return null
        val cuesJson = root.get("cues") as? JsonArray ?: return null
        if (cuesJson.size() == 0 || cuesJson.size() > MAX_CUES) return null
        var previousId = Int.MIN_VALUE
        var previousStart = Long.MIN_VALUE
        val cues = ArrayList<CaptionCue>(cuesJson.size())
        for (element in cuesJson) {
            val cue = element as? JsonObject ?: return null
            if (cue.keySet() != cueKeys && (format != VIDEO_CAPTIONS_FORMAT || cue.keySet() != cueKeys + "words")) return null
            val id = cue.int("id") ?: return null
            val start = cue.long("startMs") ?: return null
            val end = cue.long("endMs") ?: return null
            val text = cue.string("text") ?: return null
            if (id <= previousId || start < 0 || end <= start || start < previousStart || text.isBlank()) return null
            previousId = id
            previousStart = start
            val words = if (cue.has("words")) {
                val array = cue.get("words") as? JsonArray ?: return null
                if (array.size() == 0 || array.size() > text.length + 1) return null
                var previousWordStart = start
                val values = ArrayList<CaptionWord>(array.size())
                for (entry in array) {
                    val word = entry as? JsonObject ?: return null
                    if (word.keySet() != wordKeys) return null
                    val value = word.string("text")?.takeIf { it.isNotBlank() } ?: return null
                    val at = word.long("startMs") ?: return null
                    val exact = word.boolean("exact") ?: return null
                    if (at < previousWordStart || at !in start..end) return null
                    previousWordStart = at
                    values += CaptionWord(value, at, exact)
                }
                if (normalizedCaptionText(values.joinToString(" ") { it.text }) != normalizedCaptionText(text)) return null
                values
            } else null
            cues += CaptionCue(id, start, end, text, words)
        }
        if (!sourceHash.equals(captionSourceHash(cues), ignoreCase = true)) return null
        return CaptionDocument(videoId, trackId, language, automatic, sourceHash, cues)
    }

    private fun decodeGroups(bytes: ByteArray, document: CaptionDocument, expectedKeyHash: String): List<CaptionGroup>? {
        val root = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: return null
        if (root.keySet() != groupKeys) return null
        if (root.string("format") != VIDEO_CAPTION_GROUPS_FORMAT) return null
        if (root.string("videoId") != document.videoId) return null
        if (root.string("trackId") != document.trackId) return null
        if (root.string("sourceHash") != document.sourceHash) return null
        if (root.string("mergeKeyHash") != expectedKeyHash) return null
        val groupsJson = root.get("groups") as? JsonArray ?: return null
        if (groupsJson.size() > document.cues.size) return null
        val cueIds = document.cues.mapTo(HashSet(document.cues.size)) { it.id }
        var previousFirst = Int.MIN_VALUE
        val groups = ArrayList<CaptionGroup>(groupsJson.size())
        for (element in groupsJson) {
            val pair = element as? JsonArray ?: return null
            if (pair.size() != 2) return null
            val first = pair.int(0) ?: return null
            val last = pair.int(1) ?: return null
            if (first > last || first <= previousFirst) return null
            if (first !in cueIds || last !in cueIds) return null
            previousFirst = first
            groups += CaptionGroup(first, last)
        }
        return groups
    }

    private fun JsonArray.int(index: Int): Int? {
        val primitive = get(index) as? JsonPrimitive ?: return null
        return if (primitive.isNumber) primitive.asInt else null
    }

    private fun JsonObject.string(name: String): String? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.asString else null
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isBoolean) primitive.asBoolean else null
    }

    private fun JsonObject.long(name: String): Long? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isNumber) primitive.asLong else null
    }

    private fun JsonObject.int(name: String): Int? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isNumber) primitive.asInt else null
    }
}
