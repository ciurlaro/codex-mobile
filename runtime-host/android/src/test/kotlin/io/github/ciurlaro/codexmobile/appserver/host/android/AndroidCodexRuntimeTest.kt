package io.github.ciurlaro.codexmobile.appserver.host.android

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class AndroidCodexRuntimeTest {
    @Test
    fun `accepts current catalogs while keeping frames bounded`(): Unit = runBlocking {
        val catalog = "{\"data\":\"${"x".repeat(4 * 1024 * 1024)}\"}"
        var received: String? = null

        readStrictJsonLines(ByteArrayInputStream("$catalog\n".toByteArray())) { received = it }

        assertEquals(catalog.length, received?.length)
        assertFailsWith<IllegalStateException> {
            readStrictJsonLines(ByteArrayInputStream(ByteArray(1_025)), maxBytes = 1_024) {}
        }
    }
}
