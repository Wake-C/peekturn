package app.peekturn.reader.webimport

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubChunkAssemblerTest {
    private fun chunk(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test fun `chunks reassemble in order`() {
        val assembler = EpubChunkAssembler()
        val first = "PK\u0003\u0004first".toByteArray()
        val second = "second".toByteArray()
        assertTrue(assembler.push(0, chunk(first)))
        assertTrue(assembler.push(1, chunk(second)))
        assertArrayEquals(first + second, assembler.finish())
    }

    @Test fun `a missing or reordered chunk is refused`() {
        val assembler = EpubChunkAssembler()
        assertFalse(assembler.push(1, chunk("second".toByteArray())))
        assertNull(assembler.finish())

        val second = EpubChunkAssembler()
        assertTrue(second.push(0, chunk("first".toByteArray())))
        assertFalse(second.push(0, chunk("again".toByteArray())))
        assertNull(second.finish())
    }

    @Test fun `an empty transfer produces no file`() {
        assertNull(EpubChunkAssembler().finish())
    }

    @Test fun `reset gives a retry a clean transfer`() {
        val assembler = EpubChunkAssembler()
        assertFalse(assembler.push(1, chunk("late".toByteArray())))
        assembler.reset()
        assertTrue(assembler.push(0, chunk("fresh".toByteArray())))
        assertArrayEquals("fresh".toByteArray(), assembler.finish())
    }
}
