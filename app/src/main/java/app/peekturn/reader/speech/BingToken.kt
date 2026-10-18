package app.peekturn.reader.speech

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The public client identifier the read-aloud handshake carries and every token is salted with. It is
 * free and key-free: the service asks for no account, key or agreement.
 */
internal const val BING_TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

/** The token generation the handshake announces; the digest below is that generation's. */
internal const val BING_TOKEN_VERSION = "2-143.0.3650.75"

private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600.0
private const val CLOCK_STEP_SECONDS = 300.0
private const val TICKS_PER_SECOND = 1e7
private const val BLOCK_BYTES = 64
private const val BLOCK_WORDS = 16
private const val ROUNDS = 64
private const val FIRST_ROUND = 14

/** A table of 32-bit words, written as the unsigned hex the published algorithm lists. */
private fun words(vararg values: Long) = IntArray(values.size) { values[it].toInt() }

/** The published SHA-256 initial state (FIPS 180-4, §5.3.3). */
private val SHA256_STATE = words(
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
)

/** The published SHA-256 round constants (FIPS 180-4, §4.2.2). */
private val SHA256_ROUND_CONSTANTS = words(
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
)

/**
 * The state the service's digest runs its first block from, and the schedule words it fixes for that
 * block. The service does not start from the published SHA-256 state, so a plain SHA-256 of the same
 * text is a different value and is answered with HTTP 403.
 */
private val TOKEN_BLOCK_STATE = words(
    0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
    0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
)

private val TOKEN_BLOCK_SCHEDULE = words(
    0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
    0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564
)

/** The service's own round constant where its first block resumes, and the folds into its schedule. */
private val TOKEN_ROUND_CONSTANT = 0xC9DC06A7.toInt()
private const val TOKEN_SIGMA0_FOLD = 0x059C0B80
private const val TOKEN_SIGMA1_FOLD = 0x000B92C0

/** The first block is continued from [FIRST_ROUND] with [TOKEN_ROUND_CONSTANT] standing in there. */
private val TOKEN_ROUND_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().also { it[FIRST_ROUND] = TOKEN_ROUND_CONSTANT }
private val TOKEN_SCHEDULE_SHIFT = TOKEN_ROUND_CONSTANT - SHA256_ROUND_CONSTANTS[FIRST_ROUND]

/** The keyed second half's mixing words: one table of coefficients and one of offsets per key. */
private val CS64_COEFFICIENTS = words(
    0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
    0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6
)

private val CS64_OFFSETS = words(
    0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
    0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167
)

private val CS64_FACTOR = 0x82893933.toInt()
private val CS64_KEY_TERM_0 = 0xF2F90B1A.toInt()
private const val CS64_KEY_TERM_1 = 0x0B1A0000
private val CS64_WORD_TWIST_EVEN = 0xEF0569FB.toInt()
private val CS64_WORD_TWIST_ODD = 0xC31713DB.toInt()
private val CS64_HEAD_SHIFT = 0x76F50F34.toInt()
private val CS64_HEAD_MIX = 0xC3EFEA97.toInt()
private val CS64_SWAP_SHIFT = 0x1881CE3C.toInt()
private val CS64_SWAP_MIX = 0x35BD1EC9.toInt()
private val CS64_STATE_MIX_1 = 0xCF98B111.toInt()
private val CS64_STATE_MIX_2 = 0x87085B9F.toInt()
private val CS64_STATE_MIX_3 = 0x12CEB96D.toInt()
private val CS64_STATE_MIX_4 = 0x257E1D83.toInt()
private val CS64_TAIL_MIX_1 = 0xA27416F5.toInt()
private val CS64_TAIL_MIX_2 = 0xD38396FF.toInt()
private val CS64_TAIL_SHIFT_1 = 0x7C932B89.toInt()
private val CS64_TAIL_SHIFT_2 = 0xBFA49F69.toInt()

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val SEVEN_BYTES = 7

/**
 * The `Sec-MS-GEC` token the read-aloud handshake requires for [unixSeconds], the only clock it
 * accepts: the timestamp is moved onto the Windows epoch, rounded down to the service's five-minute
 * step and written as its 100-nanosecond ticks.
 *
 * The token's second half is the service's digest of that text, which is SHA-256 shaped but is not the
 * SHA-256 of it: the first block runs from a fixed state and a fixed schedule instead of the published
 * initial state, so this app builds the digest from those fixed words rather than calling a hash
 * library — a plain SHA-256 hash of the same text is answered with HTTP 403. Its first half is the
 * same text through the service's keyed mixing, keyed with the digest's own first two words.
 *
 * This is an independent implementation of the published algorithm: nothing is vendored, and no
 * licence, creator or availability of the service is claimed. It is pure — a timestamp in, a token
 * out — so it needs no network and is checked against known vectors.
 */
internal fun bingSecMsGec(unixSeconds: Double): String {
    val data = (bingTicks(unixSeconds) + BING_TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
    val digest = tokenDigest(data)
    return upperHex(cs64Digest(data, digest)) + "-" + upperHex(digest)
}

/**
 * The service's timestamp: seconds on the Windows epoch rounded down to its five-minute step, scaled to
 * 100-nanosecond ticks. The scaling is done in IEEE-754 doubles in the service's own order so its
 * rounding is matched exactly, and the tick count is then printed as the exact digits of that value.
 */
private fun bingTicks(unixSeconds: Double): String {
    var base = unixSeconds + WINDOWS_EPOCH_SECONDS
    base -= base % CLOCK_STEP_SECONDS
    return BigDecimal(base * TICKS_PER_SECOND).setScale(0, RoundingMode.HALF_EVEN).toBigInteger().toString()
}

/**
 * The service's digest of [data]: its own first block, then the published SHA-256 block function over
 * everything the padding adds.
 */
private fun tokenDigest(data: ByteArray): ByteArray {
    val tail = paddedTail(data)
    var state = tokenFirstBlock(data)
    var offset = 0
    while (offset < tail.size) {
        val schedule = scheduleOf(tail, offset)
        state = addWords(state, rounds(state, schedule, 0, ROUNDS, SHA256_ROUND_CONSTANTS))
        offset += BLOCK_BYTES
    }
    return bigEndianBytes(state)
}

/** The text from its eighth byte on, padded and closed with the service's own bit length. */
private fun paddedTail(data: ByteArray): ByteArray {
    val length = 57 + data.size
    val tail = ByteArray(data.size - SEVEN_BYTES + 1 + Math.floorMod(55 - length, BLOCK_BYTES) + 8)
    data.copyInto(tail, 0, SEVEN_BYTES)
    tail[data.size - SEVEN_BYTES] = 0x80.toByte()
    var bits = length.toLong() * 8
    for (index in tail.size - 1 downTo tail.size - 8) {
        tail[index] = bits.toByte()
        bits = bits ushr 8
    }
    return tail
}

/**
 * The service's first block: its fixed schedule words, the text's first seven bytes where the block
 * ends, and the round the block resumes at once those bytes are in the working registers.
 */
private fun tokenFirstBlock(data: ByteArray): IntArray {
    val schedule = IntArray(ROUNDS)
    schedule[FIRST_ROUND] = bigEndianInt(data, 0, 3)
    schedule[15] = bigEndianInt(data, 3, 4)
    for (index in 16 until 30) {
        var word = TOKEN_BLOCK_SCHEDULE[index - 16]
        // The published schedule mixes each word from the two before it, the one seven back, the one
        // fifteen back and the one sixteen back; the fixed table already carries the fixed parts.
        word += if (index == 16) smallSigma1(schedule[14]) xor TOKEN_SIGMA1_FOLD else smallSigma1(schedule[index - 2])
        if (index >= 21) word += schedule[index - 7]
        if (index == 29) word += smallSigma0(schedule[14]) xor TOKEN_SIGMA0_FOLD
        schedule[index] = word
    }
    schedule[30] = smallSigma1(schedule[28]) + schedule[23] + smallSigma0(schedule[15]) + schedule[14] + TOKEN_SCHEDULE_SHIFT
    expandSchedule(schedule, 31)
    return addWords(SHA256_STATE, rounds(TOKEN_BLOCK_STATE, schedule, FIRST_ROUND, ROUNDS, TOKEN_ROUND_CONSTANTS))
}

/** The published schedule expansion (FIPS 180-4, §6.2.2 step 1) from [from] to the end. */
private fun expandSchedule(schedule: IntArray, from: Int) {
    for (index in from until ROUNDS) {
        schedule[index] = smallSigma1(schedule[index - 2]) + schedule[index - 7] +
            smallSigma0(schedule[index - 15]) + schedule[index - 16]
    }
}

/** One block's schedule, read from [data] at [offset] and then expanded. */
private fun scheduleOf(data: ByteArray, offset: Int): IntArray {
    val schedule = IntArray(ROUNDS)
    for (word in 0 until BLOCK_WORDS) schedule[word] = bigEndianInt(data, offset + word * 4, 4)
    expandSchedule(schedule, BLOCK_WORDS)
    return schedule
}

/** The published round function (FIPS 180-4, §6.2.2 step 3), from round [from] to [to]. */
private fun rounds(state: IntArray, schedule: IntArray, from: Int, to: Int, constants: IntArray): IntArray {
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]
    for (index in from until to) {
        val first = h + bigSigma1(e) + ((e and f) xor (e.inv() and g)) + constants[index] + schedule[index]
        val second = bigSigma0(a) + ((a and b) xor (a and c) xor (b and c))
        h = g
        g = f
        f = e
        e = d + first
        d = c
        c = b
        b = a
        a = first + second
    }
    return intArrayOf(a, b, c, d, e, f, g, h)
}

private fun smallSigma0(value: Int) = rotateRight(value, 7) xor rotateRight(value, 18) xor (value ushr 3)
private fun smallSigma1(value: Int) = rotateRight(value, 17) xor rotateRight(value, 19) xor (value ushr 10)
private fun bigSigma0(value: Int) = rotateRight(value, 2) xor rotateRight(value, 13) xor rotateRight(value, 22)
private fun bigSigma1(value: Int) = rotateRight(value, 6) xor rotateRight(value, 11) xor rotateRight(value, 25)

private fun rotateRight(value: Int, bits: Int) = (value ushr bits) or (value shl (32 - bits))

private fun rotateHalf(value: Int) = (value shl 16) or (value ushr 16)

private fun addWords(left: IntArray, right: IntArray) = IntArray(left.size) { left[it] + right[it] }

/**
 * The token's first half: the same text mixed with a key taken from the digest's own first two words.
 * A fixed prelude of coefficient and offset terms starts both chains, each text word adds a term to
 * each chain, and every pair of terms then advances an offset chain and a state chain through the same
 * rotate-multiply steps. Only the two chain ends are folded together, which is what the token carries.
 */
private fun cs64Digest(data: ByteArray, digest: ByteArray): ByteArray {
    val keyA = littleEndianInt(digest, 0) or 1
    val keyB = littleEndianInt(digest, 4) or 1
    val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
    val words = littleEndianWords(data)
    val terms = IntArray(CS64_COEFFICIENTS.size + words.size)
    val offsets = IntArray(terms.size)
    for (index in CS64_COEFFICIENTS.indices) {
        terms[index] = CS64_COEFFICIENTS[index] * factors[index % 2]
        offsets[index] = CS64_OFFSETS[index]
    }
    for (index in words.indices) {
        val even = index % 2 == 0
        var term = words[index] * (if (even) keyA else keyB)
        var offset = rotateHalf(words[index]) * (if (even) CS64_WORD_TWIST_EVEN else CS64_WORD_TWIST_ODD)
        if (index == 0) {
            term += CS64_KEY_TERM_0 * factors[0]
            offset += CS64_KEY_TERM_1
        }
        terms[CS64_COEFFICIENTS.size + index] = term
        offsets[CS64_COEFFICIENTS.size + index] = offset
    }
    var swapAcc = 0
    var swapLast = 0
    var state = 0
    var acc = 0
    for (index in terms.indices step 2) {
        val x = terms[index]
        val y = terms[index + 1]
        var shuffled = rotateHalf(offsets[index] + x)
        var mixed = shuffled * CS64_HEAD_SHIFT
        swapAcc += rotateHalf(mixed) * CS64_HEAD_MIX + mixed
        shuffled = rotateHalf(offsets[index + 1] + y)
        mixed = shuffled * CS64_SWAP_SHIFT
        swapLast = rotateHalf(mixed) * CS64_SWAP_MIX + mixed
        swapAcc += swapLast

        state = state * keyA + x
        state = rotateHalf(state) * CS64_STATE_MIX_1
        state = rotateHalf(state) * CS64_STATE_MIX_2
        state = rotateHalf(state) * CS64_STATE_MIX_3
        state = rotateHalf(state) * CS64_STATE_MIX_4
        acc += state
        var tail = state * keyB + y
        tail = rotateHalf(tail) * CS64_TAIL_MIX_1
        tail = rotateHalf(tail) * CS64_TAIL_MIX_2
        state = rotateHalf(rotateHalf(tail) * CS64_TAIL_SHIFT_1) * CS64_TAIL_SHIFT_2
        acc += state
    }
    return littleEndianBytes(swapLast xor state, swapAcc xor acc)
}

/** The text's little-endian words, led by the zero byte the service's keying starts from, in even count. */
private fun littleEndianWords(data: ByteArray): IntArray {
    val bytes = ByteArray(data.size + 1 + Math.floorMod(-(data.size + 1), 4))
    data.copyInto(bytes, 1)
    val used = bytes.size / 4
    val words = IntArray(if (used % 2 == 0) used else used + 1)
    for (index in 0 until used) words[index] = littleEndianInt(bytes, index * 4)
    return words
}

private fun bigEndianInt(data: ByteArray, offset: Int, count: Int): Int {
    var value = 0
    for (index in 0 until count) value = (value shl 8) or (data[offset + index].toInt() and 0xFF)
    return value
}

private fun littleEndianInt(data: ByteArray, offset: Int): Int {
    var value = 0
    for (index in 3 downTo 0) value = (value shl 8) or (data[offset + index].toInt() and 0xFF)
    return value
}

private fun bigEndianBytes(words: IntArray): ByteArray {
    val bytes = ByteArray(words.size * 4)
    for (index in words.indices) {
        for (byte in 0 until 4) bytes[index * 4 + byte] = (words[index] ushr (24 - byte * 8)).toByte()
    }
    return bytes
}

private fun littleEndianBytes(first: Int, second: Int): ByteArray {
    val bytes = ByteArray(8)
    for (index in 0 until 8) {
        val word = if (index < 4) first else second
        bytes[index] = (word ushr ((index % 4) * 8)).toByte()
    }
    return bytes
}

private fun upperHex(bytes: ByteArray) = buildString(bytes.size * 2) {
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0F])
    }
}
