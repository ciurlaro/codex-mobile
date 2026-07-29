package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RuntimeProcessOutputTest {
    @Test
    fun productionOutputPathEnforcesUtf8LimitsAndDeletesThePrivateSpool(): Unit = runBlocking {
        val valid = testFile("valid")
        valid.write("""{"id":1}""" + "\r\n")
        val received = mutableListOf<String>()

        consumeProcessOutput(valid, processIsAlive = { false }, onLine = received::add)

        assertEquals(listOf("""{"id":1}"""), received)
        assertFalse(SystemFileSystem.exists(valid))

        val malformed = testFile("malformed")
        malformed.write(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()))
        assertFailsWith<IllegalArgumentException> {
            consumeProcessOutput(malformed, processIsAlive = { false }) {}
        }
        assertFalse(SystemFileSystem.exists(malformed))

        val oversized = testFile("oversized")
        oversized.write(ByteArray(17))
        assertFailsWith<IllegalStateException> {
            consumeProcessOutput(oversized, processIsAlive = { false }, maxBytes = 16) {}
        }
        assertFalse(SystemFileSystem.exists(oversized))
    }

    @Test
    fun readsBytesAppendedWhileTheProcessIsAlive(): Unit = runBlocking {
        val outputFile = testFile("live").also { it.write(ByteArray(0)) }
        val received = mutableListOf<String>()
        var alive = true
        val consumer = async {
            consumeProcessOutput(outputFile, processIsAlive = { alive }, onLine = received::add)
        }

        delay(50)
        outputFile.write("""{"id":2}""" + "\n")
        delay(50)
        alive = false
        withTimeout(2_000) { consumer.await() }

        assertEquals(listOf("""{"id":2}"""), received)
        assertFalse(SystemFileSystem.exists(outputFile))
    }

    private fun testFile(name: String): Path {
        val directory = Path("build", "runtime-output-test")
        SystemFileSystem.createDirectories(directory)
        return Path(directory, "$name.stdout")
    }

    private fun Path.write(value: String) = write(value.encodeToByteArray())

    private fun Path.write(value: ByteArray) {
        val output = SystemFileSystem.sink(this).buffered()
        try {
            output.write(value)
        } finally {
            output.close()
        }
    }
}
