package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexRuntimeFramingTest {
    @Test
    fun acceptsChunkedCRLFFramesAndEnforcesTheByteLimit() {
        val catalog = "{\"data\":\"${"x".repeat(4 * 1024 * 1024)}\"}"
        val received = mutableListOf<String>()
        val framer = StrictJsonLineFramer()
        val wire = "$catalog\r\n".encodeToByteArray()

        framer.accept(wire.copyOfRange(0, 17), received::add)
        framer.accept(wire.copyOfRange(17, wire.size), received::add)

        assertEquals(listOf(catalog), received)
        assertFailsWith<IllegalStateException> {
            StrictJsonLineFramer(1_024).accept(ByteArray(1_025)) {}
        }
    }

    @Test
    fun rejectsMalformedUTF8() {
        assertFailsWith<IllegalArgumentException> {
            StrictJsonLineFramer().accept(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte())) {}
        }
    }
}
