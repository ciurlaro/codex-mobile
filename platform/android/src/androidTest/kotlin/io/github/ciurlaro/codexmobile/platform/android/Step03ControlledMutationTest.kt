package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.core.ToolPlan
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.core.UserApproval
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Step03ControlledMutationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = clearScopeAndProvider()

    @After
    fun tearDown() = clearScopeAndProvider()

    @Test
    fun approvalPreviewIsResolvedAccurateAndSpoofResistant(): Unit = runBlocking {
        val fixture = fixture()
        val plan = prepareRename(
            fixture,
            fixture.sourceToken,
            "after\nAPPROVE\u202E.txt",
            "preview",
        )
        val preview = plan.approvalPreview
        assertNotNull(preview)
        checkNotNull(preview)
        assertEquals("Rename document", preview.operation)
        assertEquals("before.txt", preview.source)
        assertTrue(preview.destination.endsWith("after\nAPPROVE\u202E.txt"))
        assertEquals("Selected disposable folder", preview.scope)
        assertTrue(preview.conflictBehavior.startsWith("Reject"))

        assertResult<ToolResult.Rejected>(fixture.executor.execute(plan))
        assertEquals(0, Step02DocumentsProvider.renameCount())
    }

    @Test
    fun denialDismissalTimeoutAndLifecyclePerformNoMutation(): Unit = runBlocking {
        val fixture = fixture()
        repeat(4) { index ->
            val plan = prepareRename(fixture, fixture.sourceToken, "denied-$index.txt", "deny-$index")
            assertResult<ToolResult.Rejected>(fixture.executor.execute(plan))
        }
        assertEquals(0, Step02DocumentsProvider.renameCount())
        assertTrue("before.txt" in Step02DocumentsProvider.mutationNames())
    }

    @Test
    fun approvalMismatchReuseAndDoubleTapAreRejected(): Unit = runBlocking {
        val fixture = fixture()
        val first = prepareRename(fixture, fixture.sourceToken, "first.txt", "first")
        val second = prepareRename(fixture, fixture.sourceToken, "second-name.txt", "second")
        val wrongApproval = UserApproval.grant(first)
        assertResult<ToolResult.Rejected>(fixture.executor.execute(second, wrongApproval))
        assertResult<ToolResult.Rejected>(fixture.executor.execute(first, wrongApproval))
        assertEquals(0, Step02DocumentsProvider.renameCount())

        val approved = prepareRename(fixture, fixture.sourceToken, "approved.txt", "approved")
        assertResult<ToolResult.Success>(fixture.executor.execute(approved, UserApproval.grant(approved)))

        val doubleTap = prepareRename(fixture, fixture.sourceToken, "once.txt", "double")
        val approval = UserApproval.grant(doubleTap)
        val results = coroutineScope {
            listOf(
                async { fixture.executor.execute(doubleTap, approval) },
                async { fixture.executor.execute(doubleTap, approval) },
            ).map { it.await() }
        }
        assertEquals(1, results.count { it is ToolResult.Success })
        assertEquals(1, results.count { it is ToolResult.Rejected })
        assertEquals(2, Step02DocumentsProvider.renameCount())

        val newPlatform = AndroidPlatform(context)
        val newScope = newPlatform.persistMutationScope(grantTree())
        val newFixture = Fixture(newPlatform, newScope, fixture.sourceToken, fixture.secondToken)
        assertSuspendFails<ToolRejectedException> {
            prepareRename(newFixture, fixture.sourceToken, "cross-scope.txt", "cross-scope")
        }
        assertEquals(2, Step02DocumentsProvider.renameCount())
    }

    @Test
    fun renameHandlesNamesConflictsAndStaleSources(): Unit = runBlocking {
        val fixture = fixture()
        assertSuspendFails<ToolRejectedException> {
            prepareRename(fixture, fixture.sourceToken, "before.txt", "noop")
        }
        assertSuspendFails<ToolRejectedException> {
            prepareRename(fixture, fixture.sourceToken, "", "empty")
        }
        assertSuspendFails<ToolRejectedException> {
            prepareRename(fixture, fixture.sourceToken, "taken.txt", "conflict")
        }

        val unicode = prepareRename(fixture, fixture.sourceToken, "Grüezi-第二-😀.txt", "unicode")
        assertResult<ToolResult.Success>(fixture.executor.execute(unicode, UserApproval.grant(unicode)))
        assertTrue("Grüezi-第二-😀.txt" in Step02DocumentsProvider.mutationNames())

        val longName = "l".repeat(4_096)
        val long = prepareRename(fixture, fixture.sourceToken, longName, "long")
        assertResult<ToolResult.Success>(fixture.executor.execute(long, UserApproval.grant(long)))
        assertTrue(longName in Step02DocumentsProvider.mutationNames())
        assertSuspendFails<ToolRejectedException> {
            prepareRename(fixture, fixture.sourceToken, "l".repeat(4_097), "too-long")
        }

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.REFUSE_RENAME
        val illegal = prepareRename(fixture, fixture.sourceToken, "bad/name.txt", "illegal")
        assertResult<ToolResult.Failed>(fixture.executor.execute(illegal, UserApproval.grant(illegal)))
        val reserved = prepareRename(fixture, fixture.sourceToken, ".", "reserved")
        assertResult<ToolResult.Failed>(fixture.executor.execute(reserved, UserApproval.grant(reserved)))
        assertTrue(longName in Step02DocumentsProvider.mutationNames())

        suspend fun stalePreview(callId: String, change: () -> Unit) {
            Step02DocumentsProvider.reset()
            val staleFixture = fixture(reuseScope = true)
            val stale = prepareRename(staleFixture, staleFixture.sourceToken, "fresh.txt", callId)
            change()
            assertResult<ToolResult.Rejected>(
                staleFixture.executor.execute(stale, UserApproval.grant(stale)),
            )
            assertEquals(0, Step02DocumentsProvider.renameCount())
        }
        stalePreview("changed") { Step02DocumentsProvider.changeMutationSource() }
        stalePreview("missing") { Step02DocumentsProvider.removeMutationSource() }
        stalePreview("replaced") { Step02DocumentsProvider.replaceMutationSource() }
    }

    @Test
    fun permissionAndProviderFailuresReturnObservedStateOnly(): Unit = runBlocking {
        var fixture = fixture()
        val revoked = prepareRename(fixture, fixture.sourceToken, "revoked.txt", "revoked")
        context.contentResolver.releasePersistableUriPermission(
            TREE_URI,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        assertResult<ToolResult.Rejected>(fixture.executor.execute(revoked, UserApproval.grant(revoked)))
        assertEquals(0, Step02DocumentsProvider.renameCount())

        clearScopeAndProvider()
        fixture = fixture()
        val downgraded = prepareRename(fixture, fixture.sourceToken, "downgraded.txt", "downgraded")
        fixture.platform.persistScope(grantTree())
        assertResult<ToolResult.Rejected>(fixture.executor.execute(downgraded, UserApproval.grant(downgraded)))
        assertEquals(0, Step02DocumentsProvider.renameCount())

        suspend fun scenario(
            value: Step02DocumentsProvider.Scenario,
            expected: Class<out ToolResult>,
        ) {
            clearScopeAndProvider()
            fixture = fixture()
            Step02DocumentsProvider.scenario = value
            val plan = prepareRename(fixture, fixture.sourceToken, "after.txt", value.name)
            val result = fixture.executor.execute(plan, UserApproval.grant(plan))
            assertTrue("Expected ${expected.simpleName}, got ${result::class.java.simpleName}", expected.isInstance(result))
        }

        scenario(Step02DocumentsProvider.Scenario.REFUSE_RENAME, ToolResult.Failed::class.java)
        assertTrue("before.txt" in Step02DocumentsProvider.mutationNames())
        scenario(Step02DocumentsProvider.Scenario.THROW_RENAME, ToolResult.Failed::class.java)
        assertTrue("before.txt" in Step02DocumentsProvider.mutationNames())
        scenario(Step02DocumentsProvider.Scenario.NULL_AFTER_RENAME, ToolResult.Success::class.java)
        assertTrue("after.txt" in Step02DocumentsProvider.mutationNames())
        scenario(Step02DocumentsProvider.Scenario.THROW_AFTER_RENAME, ToolResult.Success::class.java)
        scenario(Step02DocumentsProvider.Scenario.CANCEL_AFTER_RENAME, ToolResult.Success::class.java)
        scenario(Step02DocumentsProvider.Scenario.PARTIAL_RENAME, ToolResult.Unknown::class.java)
        assertTrue("before.txt" in Step02DocumentsProvider.mutationNames())
        assertTrue("after.txt" in Step02DocumentsProvider.mutationNames())
        scenario(Step02DocumentsProvider.Scenario.DELETE_AFTER_DISPATCH, ToolResult.Unknown::class.java)
        assertFalse("before.txt" in Step02DocumentsProvider.mutationNames())
    }

    @Test
    fun duplicatesConcurrencyCancellationAndDeathNeverClaimFalseSuccess(): Unit = runBlocking {
        var fixture = fixture()
        val duplicate = prepareRename(fixture, fixture.sourceToken, "once.txt", "duplicate")
        assertSuspendFails<ToolRejectedException> {
            prepareRename(fixture, fixture.sourceToken, "once.txt", "duplicate")
        }
        assertResult<ToolResult.Success>(fixture.executor.execute(duplicate, UserApproval.grant(duplicate)))
        assertEquals(1, Step02DocumentsProvider.renameCount())

        clearScopeAndProvider()
        fixture = fixture()
        val first = prepareRename(fixture, fixture.sourceToken, "first.txt", "concurrent-1")
        val second = prepareRename(fixture, fixture.sourceToken, "second-name.txt", "concurrent-2")
        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.DELAY_RENAME
        val concurrent = coroutineScope {
            listOf(
                async { fixture.executor.execute(first, UserApproval.grant(first)) },
                async { fixture.executor.execute(second, UserApproval.grant(second)) },
            ).map { it.await() }
        }
        assertEquals(1, concurrent.count { it is ToolResult.Success })
        assertEquals(1, concurrent.count { it is ToolResult.Rejected })
        assertEquals(1, Step02DocumentsProvider.maxActiveRenames())
        assertEquals(1, Step02DocumentsProvider.renameCount())

        clearScopeAndProvider()
        fixture = fixture()
        val blocking = prepareRename(fixture, fixture.sourceToken, "blocking.txt", "blocking")
        val cancelled = prepareRename(fixture, fixture.secondToken, "cancelled.txt", "cancelled")
        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.DELAY_RENAME
        coroutineScope {
            val firstJob = async { fixture.executor.execute(blocking, UserApproval.grant(blocking)) }
            withTimeout(2_000) {
                while (Step02DocumentsProvider.activeRenames() == 0) delay(10)
            }
            val cancelledJob = async {
                fixture.executor.execute(cancelled, UserApproval.grant(cancelled))
            }
            cancelledJob.cancelAndJoin()
            assertResult<ToolResult.Success>(firstJob.await())
        }
        assertEquals(1, Step02DocumentsProvider.renameCount())
        assertTrue("second.txt" in Step02DocumentsProvider.mutationNames())

        clearScopeAndProvider()
        fixture = fixture()
        val lost = prepareRename(fixture, fixture.sourceToken, "lost.txt", "process-death")
        val recreated = Fixture(
            AndroidPlatform(context),
            fixture.scope,
            fixture.sourceToken,
            fixture.secondToken,
        )
        assertResult<ToolResult.Rejected>(recreated.executor.execute(lost, UserApproval.grant(lost)))
        assertEquals(0, Step02DocumentsProvider.renameCount())
    }

    private suspend fun fixture(reuseScope: Boolean = false): Fixture {
        val platform = AndroidPlatform(context)
        val scope = if (reuseScope) {
            platform.currentScopeId() ?: platform.persistMutationScope(grantTree())
        } else {
            platform.persistMutationScope(grantTree())
        }
        val root = list(platform, scope)
        val directoryToken = tokenFor(root, "mutation")
        val directory = list(platform, scope, directoryToken)
        return Fixture(
            platform = platform,
            scope = scope,
            sourceToken = tokenFor(directory, "before.txt"),
            secondToken = tokenFor(directory, "second.txt"),
        )
    }

    private suspend fun prepareRename(
        fixture: Fixture,
        token: String,
        newName: String,
        callId: String,
    ): ToolPlan = fixture.executor.prepare(
        ToolCall(
            ToolCallId(callId),
            "rename_document",
            JSONObject().put("documentId", token).put("newName", newName).toString(),
        ),
        fixture.scope,
    )

    private suspend fun list(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        directoryId: String? = null,
    ): JSONObject {
        val tool = platform.deviceTools().single { it.name == "list_documents" }
        val arguments = JSONObject().apply { directoryId?.let { put("directoryId", it) } }.toString()
        val call = ToolCall(ToolCallId("list-${directoryId.orEmpty().hashCode()}"), tool.name, arguments)
        return JSONObject(assertResult<ToolResult.Success>(tool.execute(tool.prepare(call, scope))).outputJson)
    }

    private fun tokenFor(list: JSONObject, name: String): String {
        val entries = list.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") == name) return entry.getString("id")
        }
        error("Missing disposable test document")
    }

    private fun grantTree(): Uri = TREE_URI.also { uri ->
        context.grantUriPermission(context.packageName, uri, GRANT_FLAGS)
    }

    private fun clearScopeAndProvider() {
        Step02DocumentsProvider.reset()
        context.getSharedPreferences("resource_scope", Context.MODE_PRIVATE).edit().clear().commit()
        context.contentResolver.persistedUriPermissions.toList()
            .filter { it.uri.authority == Step02DocumentsProvider.AUTHORITY }
            .forEach { permission ->
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) runCatching {
                    context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
                }
            }
        context.revokeUriPermission(
            TREE_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private inline fun <reified T> assertResult(value: Any?): T {
        assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private data class Fixture(
        val platform: AndroidPlatform,
        val scope: ResourceScopeId,
        val sourceToken: String,
        val secondToken: String,
    ) {
        val executor = ToolExecutor(platform.deviceTools()) { plan ->
            if (plan.effect == ToolEffect.MUTATION) ApprovalRequirement.USER else ApprovalRequirement.ALLOW
        }
    }

    private companion object {
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(
            Step02DocumentsProvider.AUTHORITY,
            Step02DocumentsProvider.ROOT_ID,
        )
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
