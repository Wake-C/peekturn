package app.peekturn.reader.learning

import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.ExplanationExample
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.model.ModelConfig
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A completed explanation is reusable for fifteen days, measured from successful completion. */
internal const val EXPLANATION_CACHE_TTL_MILLIS = 15L * 24 * 60 * 60 * 1000

/** The private on-disk budget. Entries are evicted only when this bound or the TTL is exceeded. */
internal const val EXPLANATION_CACHE_BYTES = 1L * 1024 * 1024 * 1024

/**
 * One encoded answer cannot exceed this. It is deliberately independent of the 1 GiB cap: the
 * response parser already bound an answer to 128,000 characters, and JSON escaping can expand it.
 */
internal const val EXPLANATION_CACHE_MAX_ENTRY_BYTES = 512L * 1024

/** The persisted record layout. Bump when the record fields change. */
internal const val EXPLANATION_CACHE_FORMAT = "explanation-cache-v1"

private val explanationCacheKeyPattern = Regex("[0-9a-f]{64}")
private val recordKeys = setOf("format", "prompt", "completedAt", "language", "answer")
private val answerKeys = setOf("term", "partOfSpeech", "ukIpa", "usIpa", "meaning", "context", "examples")
private val exampleKeys = setOf("text", "translation")

/**
 * Everything that can change a provider answer. It deliberately contains no display-only profile
 * name and no API key, so rotating a key does not discard an otherwise identical answer.
 *
 * The source still receives the raw caller context; identity is the provider's own clipped request,
 * so two snippets that clip to the same request share one result without a second context rule.
 */
internal class ExplanationRequest(
    val context: LookupContext,
    val profileId: String,
    val endpoint: String,
    val model: String,
    val temperature: Double,
    val language: ExplanationLanguage
) {
    val cacheKey: String = explanationCacheKey(this)

    /** Without a stable occurrence locator, empty context could smear one answer across words. */
    val hasContextEvidence: Boolean
        get() = context.before.isNotBlank() || context.after.isNotBlank()

    override fun equals(other: Any?): Boolean = other is ExplanationRequest && other.cacheKey == cacheKey
    override fun hashCode(): Int = cacheKey.hashCode()

    companion object {
        fun of(
            context: LookupContext,
            profileId: String,
            config: ModelConfig,
            language: ExplanationLanguage
        ) = ExplanationRequest(
            context = context,
            profileId = profileId,
            endpoint = config.endpoint.trim(),
            model = config.model.trim(),
            temperature = config.temperature,
            language = language
        )
    }
}

internal data class CachedExplanation(val answer: Explanation, val completedAt: Long)

/**
 * Persistent, app-private explanation reuse. A name is an opaque SHA-256 of the canonical request,
 * so no selected text, title or credential reaches a filename or a log. Only a complete validated
 * answer is encoded; expiry is absolute from [CachedExplanation.completedAt], and reading a hit
 * never extends it. The oldest access is evicted only after the disk budget is exceeded.
 */
internal class ExplanationCache(
    private val directory: File,
    private val maxBytes: Long = EXPLANATION_CACHE_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    /** Lazily counted, so a directory of entries is scanned only when the bound is checked. */
    private var knownBytes: Long? = null

    init {
        require(maxBytes > 0)
        directory.mkdirs()
    }

    /** The cache-wide clear generation, observed by sessions so memory cannot outlive a clear. */
    fun epoch(): Long = generation.get()

    suspend fun get(request: ExplanationRequest, expectedEpoch: Long): CachedExplanation? = withContext(io) {
        if (!request.hasContextEvidence) return@withContext null
        if (expectedEpoch != generation.get()) return@withContext null
        val key = request.cacheKey
        if (!explanationCacheKeyPattern.matches(key)) return@withContext null
        mutex.withLock {
            if (expectedEpoch != generation.get()) return@withLock null
            val file = entry(key)
            if (!file.isFile) return@withLock null
            val length = file.length()
            if (length <= 0L || length > EXPLANATION_CACHE_MAX_ENTRY_BYTES || length > maxBytes) {
                if (file.delete()) subtract(length)
                return@withLock null
            }
            val cached = runCatching { decode(file.readBytes(), request) }.getOrNull()
            if (cached == null) {
                if (file.delete()) subtract(length)
                return@withLock null
            }
            // LRU only; the stored completion time remains the sole expiry authority.
            file.setLastModified(now())
            cached
        }
    }

    /**
     * Writes a complete [answer] atomically. [expectedEpoch] ties the write to the request that
     * produced it: a clear that happened meanwhile wins, and this older request cannot repopulate.
     */
    suspend fun put(
        request: ExplanationRequest,
        answer: Explanation,
        expectedEpoch: Long
    ): CachedExplanation? = withContext(io) {
        if (!request.hasContextEvidence) return@withContext null
        if (expectedEpoch != generation.get()) return@withContext null
        val validated = answer.validated(request.language) ?: return@withContext null
        val completedAt = now()
        val bytes = encode(request.language, validated, completedAt).toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size.toLong() > EXPLANATION_CACHE_MAX_ENTRY_BYTES || bytes.size.toLong() > maxBytes) {
            return@withContext null
        }
        mutex.withLock {
            if (expectedEpoch != generation.get()) return@withLock null
            directory.mkdirs()
            val target = entry(request.cacheKey)
            val previousLength = if (target.isFile) target.length() else 0L
            val totalBefore = totalBytes()
            val temporary = File.createTempFile("write-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                // A clear may have advanced while the bytes were being flushed.
                if (expectedEpoch != generation.get()) {
                    temporary.delete()
                    return@withLock null
                }
                moveAtomically(temporary, target)
            } catch (_: Exception) {
                temporary.delete()
                return@withLock null
            }
            target.setLastModified(completedAt)
            knownBytes = (totalBefore - previousLength + bytes.size.toLong()).coerceAtLeast(0L)
            evict(protected = target)
            CachedExplanation(validated, completedAt)
        }
    }

    /** Retry invalidates the old answer before its replacement request starts. */
    suspend fun remove(request: ExplanationRequest) = withContext(io) {
        if (!request.hasContextEvidence) return@withContext
        mutex.withLock {
            val file = entry(request.cacheKey)
            val length = file.length()
            if (file.isFile && file.delete()) subtract(length)
        }
    }

    suspend fun clear() {
        // Advance before waiting for disk work, so an in-flight completion is refused immediately.
        generation.incrementAndGet()
        withContext(io) {
            mutex.withLock {
                directory.listFiles()?.forEach { if (it.isFile) it.delete() }
                knownBytes = 0L
            }
        }
    }

    suspend fun sizeBytes(): Long = withContext(io) {
        mutex.withLock { totalBytes() }
    }

    private fun entry(key: String) = File(directory, "$key.json")

    private fun records(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList().orEmpty()

    private fun totalBytes(): Long = knownBytes ?: records().sumOf { it.length() }.also { knownBytes = it }

    private fun subtract(bytes: Long) {
        knownBytes = knownBytes?.let { (it - bytes).coerceAtLeast(0L) }
    }

    /** Oldest access first, with the name breaking timestamp ties; the new entry is never evicted. */
    private fun evict(protected: File) {
        var total = totalBytes()
        if (total <= maxBytes) return
        // Trim to 95% in one batch: the next ~5% of writes need no directory enumeration.
        val target = maxBytes - maxBytes / 20
        for (file in records().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
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

    private fun encode(language: ExplanationLanguage, answer: Explanation, completedAt: Long): String {
        val examples = JsonArray().apply {
            answer.examples.forEach { example ->
                add(JsonObject().apply {
                    addProperty("text", example.text)
                    addProperty("translation", example.translation)
                })
            }
        }
        val value = JsonObject().apply {
            addProperty("term", answer.term)
            addProperty("partOfSpeech", answer.partOfSpeech)
            addProperty("ukIpa", answer.ukIpa)
            addProperty("usIpa", answer.usIpa)
            addProperty("meaning", answer.meaning)
            addProperty("context", answer.context)
            add("examples", examples)
        }
        return JsonObject().apply {
            addProperty("format", EXPLANATION_CACHE_FORMAT)
            addProperty("prompt", EXPLANATION_PROMPT_GENERATION)
            addProperty("completedAt", completedAt)
            addProperty("language", language.name)
            add("answer", value)
        }.toString()
    }

    /** Structurally validates a persisted record and its language before it can become a hit. */
    private fun decode(bytes: ByteArray, request: ExplanationRequest): CachedExplanation? {
        val root = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: return null
        if (root.keySet() != recordKeys) return null
        if (root.string("format") != EXPLANATION_CACHE_FORMAT) return null
        if (root.string("prompt") != EXPLANATION_PROMPT_GENERATION) return null
        val completedAt = root.long("completedAt") ?: return null
        val current = now()
        // A future completion cannot be trusted: it would otherwise stay valid after a clock rollback.
        if (completedAt <= 0L || completedAt > current) return null
        if (current - completedAt >= EXPLANATION_CACHE_TTL_MILLIS) return null
        if (root.string("language") != request.language.name) return null
        val answerJson = root.get("answer") as? JsonObject ?: return null
        if (answerJson.keySet() != answerKeys) return null

        val term = answerJson.string("term") ?: return null
        val partOfSpeech = answerJson.string("partOfSpeech") ?: return null
        val ukIpa = answerJson.string("ukIpa") ?: return null
        val usIpa = answerJson.string("usIpa") ?: return null
        val meaning = answerJson.string("meaning") ?: return null
        val context = answerJson.string("context") ?: return null
        val examplesJson = answerJson.get("examples") as? JsonArray ?: return null
        if (examplesJson.size() !in 1..2) return null
        val examples = ArrayList<ExplanationExample>(examplesJson.size())
        for (element in examplesJson) {
            val example = element as? JsonObject ?: return null
            if (example.keySet() != exampleKeys) return null
            examples += ExplanationExample(
                text = example.string("text") ?: return null,
                translation = example.string("translation") ?: return null
            )
        }
        val answer = Explanation(term, partOfSpeech, ukIpa, usIpa, meaning, context, examples)
            .validated(request.language) ?: return null
        return CachedExplanation(answer, completedAt)
    }

    private fun JsonObject.string(name: String): String? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.asString else null
    }

    private fun JsonObject.long(name: String): Long? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return if (primitive.isNumber) primitive.asLong else null
    }

    /** Mirrors the parser's completeness rules; incomplete or oversized answers are not reusable. */
    private fun Explanation.validated(language: ExplanationLanguage): Explanation? =
        if (isCompleteFor(language)) copy(examples = examples.map { it.copy() }) else null

    companion object {
        /** Kept out of Android backup and device transfer along with the rest of no-backup storage. */
        const val DIRECTORY_NAME = "explanations"

        fun directory(noBackupFiles: File): File = File(noBackupFiles, DIRECTORY_NAME)
    }
}

/**
 * A stable, opaque key over the exact request the model receives plus the surrounding identity.
 * Length-prefixed fields make the canonical form unambiguous even when book text contains spaces or
 * separators. Neither the API key nor plaintext context is part of a filename.
 */
private fun explanationCacheKey(request: ExplanationRequest): String {
    val context = clipContext(request.context)
    val material = StringBuilder()
    fun field(value: String) {
        material.append(value.length).append(':').append(value).append('\n')
    }
    field("margin-explanation")
    field(EXPLANATION_CACHE_FORMAT)
    field(EXPLANATION_PROMPT_GENERATION)
    field(context.bookId.toString())
    field(context.chapterKey)
    field(context.selection)
    field(context.before)
    field(context.after)
    field(context.bookTitle)
    field(context.chapterTitle)
    field(request.language.name)
    field(request.profileId)
    field(request.endpoint)
    field(request.model)
    field(request.temperature.toString())
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toString().toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"
