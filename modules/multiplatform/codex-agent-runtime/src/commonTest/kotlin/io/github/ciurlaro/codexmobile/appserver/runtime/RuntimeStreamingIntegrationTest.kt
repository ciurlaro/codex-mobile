package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RuntimeStreamingIntegrationTest {
    @Test
    fun slowConsumerReceivesBurstInOrderBeforeExitAndEof(): Unit = runBlocking {
        newRuntime("burst").use { runtime ->
            val events = mutableListOf<CodexRuntimeEvent>()
            val finished = async {
                withTimeout(15_000) {
                    runtime.events
                        .onEach {
                            events += it
                            delay(2)
                        }
                        .first { it is CodexRuntimeEvent.EndOfFile }
                }
            }

            runtime.start()
            runtime.send(
                CodexJsonLine(
                    """i=0; while [ ${'$'}i -lt 128 ]; do printf '{"id":%s}\n' "${'$'}i"; i=${'$'}((i+1)); done; exit""",
                ),
            )
            finished.await()

            assertEquals(
                (0 until 128).map { """{"id":$it}""" },
                events.filterIsInstance<CodexRuntimeEvent.Received>().map { it.line.value },
            )
            assertIs<CodexRuntimeEvent.Exited>(events[events.lastIndex - 1])
            assertIs<CodexRuntimeEvent.EndOfFile>(events.last())
        }
    }

    @Test
    fun malformedUtf8FailsWithoutNormalEof(): Unit = runBlocking {
        newRuntime("malformed").use { runtime ->
            runtime.start()
            val failed = async {
                withTimeout(5_000) {
                    runtime.events.first { it is CodexRuntimeEvent.IoFailure }
                }
            }

            runtime.send(CodexJsonLine("""printf '\303('; exit"""))

            assertIs<CodexRuntimeEvent.IoFailure>(failed.await())
            assertNull(
                withTimeoutOrNull(250) {
                    runtime.events.first { it is CodexRuntimeEvent.EndOfFile }
                },
            )
        }
    }

    @Test
    fun closeUnblocksABackpressuredFeed(): Unit = runBlocking {
        val runtime = newRuntime("shutdown")
        runtime.start()
        runtime.send(
            CodexJsonLine(
                """i=0; while [ ${'$'}i -lt 512 ]; do printf '{"id":%s}\n' "${'$'}i"; i=${'$'}((i+1)); done; exit""",
            ),
        )
        delay(100)

        withTimeout(5_000) {
            withContext(Dispatchers.Default) { runtime.close() }
        }
    }

    private fun newRuntime(name: String): CodexRuntime {
        val root = Path("build", "runtime-streaming-test", name)
        val certificates = Path(root, "certificates")
        SystemFileSystem.createDirectories(certificates)
        val certificate = Path(certificates, "test.pem")
        SystemFileSystem.sink(certificate).buffered().use { output ->
            output.write("test certificate".encodeToByteArray())
        }
        return CodexAppServerRuntime(
            CodexRuntimeConfiguration(
                executable = Path("/bin/sh"),
                packagedRuntimeEnvironment = null,
                applicationDirectory = Path(root, "home"),
                privateDirectory = Path(root, "private"),
                temporaryDirectory = Path(root, "tmp"),
                certificateSources = listOf(certificate),
                sqliteDriver = BundledSQLiteDriver(),
                platformEnvironment = mapOf("PATH" to "/usr/bin:/bin"),
                proxyPassword = "streaming-test-token-$name",
            ),
        )
    }
}
