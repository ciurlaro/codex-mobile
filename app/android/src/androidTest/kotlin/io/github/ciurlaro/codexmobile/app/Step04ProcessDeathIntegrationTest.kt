package io.github.ciurlaro.codexmobile.app

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class Step04ProcessDeathIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun processDeathBoundaryHarness(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == "harness")
        requirePhysicalDevice()
        val boundary = Boundary.parse(requireNotNull(arguments.getString(BOUNDARY_ARGUMENT)))
        val runId = requireNotNull(arguments.getString(RUN_ARGUMENT)).takeIf { it.matches(RUN_PATTERN) }
            ?: error("Invalid fault-test run")
        val fixture = fixture()
        val recordId = recordId(runId, boundary)
        assertNull(fixture.journal.find(recordId))

        if (boundary != Boundary.BEFORE_PREPARED) {
            val plan = fixture.prepare(boundary)
            val record = MutationRecord(
                recordId,
                plan.call.id,
                plan.call.name,
                plan.scopeId,
                plan.fingerprint,
                checkNotNull(fixture.renameTool.recoveryPayload(plan)),
                MutationState.PREPARED,
            )
            fixture.journal.create(record)
            if (boundary != Boundary.PREPARED) {
                fixture.journal.transition(
                    record.id,
                    MutationState.PREPARED,
                    MutationState.EXECUTING,
                )
            }
            when (boundary) {
                Boundary.PROVIDER_SUCCEEDED -> assertTrue(
                    fixture.renameTool.execute(plan) is ToolResult.Success,
                )

                Boundary.BEFORE_PREPARED, Boundary.PREPARED, Boundary.EXECUTING -> Unit
            }
        }

        Log.i(PROCESS_DEATH_TAG, "boundary ready:${boundary.argument}")
        delay(PROCESS_DEATH_WATCHDOG_MILLIS)
        throw AssertionError("Expected external force-stop at ${boundary.argument}")
    }

    @Test
    fun verifyBoundaryAfterRestart(): Unit = runBlocking {
        assumeTrue(arguments.getString(MODE_ARGUMENT) == "verify")
        requirePhysicalDevice()
        val boundary = Boundary.parse(requireNotNull(arguments.getString(BOUNDARY_ARGUMENT)))
        val runId = requireNotNull(arguments.getString(RUN_ARGUMENT)).takeIf { it.matches(RUN_PATTERN) }
            ?: error("Invalid fault-test run")
        val fixture = fixture()
        fixture.executor.reconcileUnresolved()
        val record = fixture.journal.find(recordId(runId, boundary))
        val names = fixture.names()
        assertTrue(UNTOUCHED in names)

        when (boundary) {
            Boundary.BEFORE_PREPARED -> {
                assertNull(record)
                assertTrue(BEFORE_PREPARED_SOURCE in names)
                assertFalse(BEFORE_PREPARED_DESTINATION in names)
            }

            Boundary.PREPARED -> verifyFailedUnchanged(
                fixture,
                checkNotNull(record),
                PREPARED_SOURCE,
                PREPARED_DESTINATION,
                names,
            )

            Boundary.EXECUTING -> verifyFailedUnchanged(
                fixture,
                checkNotNull(record),
                EXECUTING_SOURCE,
                EXECUTING_DESTINATION,
                names,
            )

            Boundary.PROVIDER_SUCCEEDED -> {
                assertEquals(MutationState.SUCCEEDED, checkNotNull(record).state)
                assertFalse(SUCCESS_SOURCE in names)
                assertTrue(SUCCESS_DESTINATION in names)
                assertTrue(fixture.journal.visible().any { it.id == record.id })
                fixture.journal.acknowledge(record.id)
            }

        }
    }

    private suspend fun verifyFailedUnchanged(
        fixture: Fixture,
        record: MutationRecord,
        source: String,
        destination: String,
        names: Set<String>,
    ) {
        assertEquals(MutationState.FAILED, record.state)
        assertTrue(source in names)
        assertFalse(destination in names)
        assertTrue(fixture.journal.visible().any { it.id == record.id })
        fixture.journal.acknowledge(record.id)
    }

    private suspend fun fixture(): Fixture {
        val application = context.applicationContext as CodexMobileApplication
        val platform = application.graph.platform
        val scope = requireNotNull(platform.currentScopeId()) {
            "Select the dedicated Step 04 disposable folder through DocumentsUI first"
        }
        check(platform.currentScopeAllowsMutations()) {
            "The dedicated Step 04 folder must have explicit mutation access"
        }
        return Fixture(platform, scope, application.graph.toolExecutor)
    }

    private inner class Fixture(
        val platform: AndroidPlatform,
        val scope: ResourceScopeId,
        val executor: io.github.ciurlaro.codexmobile.core.ToolExecutor,
    ) {
        val journal = platform.mutationJournal()
        val renameTool = platform.deviceTools().single { it.name == "rename_document" }

        suspend fun names(): Set<String> {
            val listTool = platform.deviceTools().single { it.name == "list_documents" }
            val call = ToolCall(ToolCallId("step04-list"), listTool.name, "{}")
            val result = listTool.execute(listTool.prepare(call, scope))
            check(result is ToolResult.Success)
            val entries = JSONObject(result.outputJson).getJSONArray("entries")
            return buildSet {
                repeat(entries.length()) { index -> add(entries.getJSONObject(index).getString("name")) }
            }
        }

        suspend fun prepare(boundary: Boundary): ToolPlan {
            val source = boundary.source ?: error("Boundary has no rename")
            val entries = listEntries()
            val token = entries.getValue(source)
            return renameTool.prepare(
                ToolCall(
                    ToolCallId("step04-${boundary.argument}"),
                    renameTool.name,
                    JSONObject()
                        .put("documentId", token)
                        .put("newName", boundary.destination)
                        .toString(),
                ),
                scope,
            )
        }

        private suspend fun listEntries(): Map<String, String> {
            val listTool = platform.deviceTools().single { it.name == "list_documents" }
            val call = ToolCall(ToolCallId("step04-list-tokens"), listTool.name, "{}")
            val result = listTool.execute(listTool.prepare(call, scope))
            check(result is ToolResult.Success)
            val entries = JSONObject(result.outputJson).getJSONArray("entries")
            return buildMap {
                repeat(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    put(entry.getString("name"), entry.getString("id"))
                }
            }
        }
    }

    private fun recordId(runId: String, boundary: Boundary) =
        MutationRecordId("step04-$runId-${boundary.argument}")

    private fun requirePhysicalDevice() {
        assumeFalse(
            "Process-death evidence requires a physical device",
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Emulator"),
        )
    }

    private enum class Boundary(
        val argument: String,
        val source: String?,
        val destination: String,
    ) {
        BEFORE_PREPARED("before_prepared", null, BEFORE_PREPARED_DESTINATION),
        PREPARED("prepared", PREPARED_SOURCE, PREPARED_DESTINATION),
        EXECUTING("executing", EXECUTING_SOURCE, EXECUTING_DESTINATION),
        PROVIDER_SUCCEEDED("provider_succeeded", SUCCESS_SOURCE, SUCCESS_DESTINATION),
        ;

        companion object {
            fun parse(value: String): Boundary = entries.single { it.argument == value }
        }
    }

    private companion object {
        const val MODE_ARGUMENT = "step04Mode"
        const val BOUNDARY_ARGUMENT = "step04Boundary"
        const val RUN_ARGUMENT = "step04Run"
        val RUN_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")
        const val PROCESS_DEATH_TAG = "CodexMobileStep04Death"
        const val PROCESS_DEATH_WATCHDOG_MILLIS = 120_000L
        const val BEFORE_PREPARED_SOURCE = "before-prepared-before.txt"
        const val BEFORE_PREPARED_DESTINATION = "before-prepared-after.txt"
        const val PREPARED_SOURCE = "prepared-before.txt"
        const val PREPARED_DESTINATION = "prepared-after.txt"
        const val EXECUTING_SOURCE = "executing-before.txt"
        const val EXECUTING_DESTINATION = "executing-after.txt"
        const val SUCCESS_SOURCE = "success-before.txt"
        const val SUCCESS_DESTINATION = "success-after.txt"
        const val UNTOUCHED = "untouched.txt"
    }
}
