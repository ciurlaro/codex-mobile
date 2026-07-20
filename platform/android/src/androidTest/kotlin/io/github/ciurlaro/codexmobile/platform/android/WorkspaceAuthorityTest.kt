package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceAuthorityTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase("workspace.sqlite")
        context.getSharedPreferences("workspace-identity", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("workspace_export_scope", Context.MODE_PRIVATE).edit().clear().commit()
        Step02DocumentsProvider.reset()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("workspace.sqlite")
        context.getSharedPreferences("workspace-identity", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("workspace_export_scope", Context.MODE_PRIVATE).edit().clear().commit()
        context.contentResolver.persistedUriPermissions.toList().forEach { permission ->
            var flags = 0
            if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) runCatching {
                context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
            }
        }
        Step02DocumentsProvider.reset()
    }

    @Test
    fun changesetsAreAtomicDiffedAndRecoverable(): Unit = runBlocking {
        val store = WorkspaceStore(context)
        val authority = WorkspaceAuthority(store)
        val apply = authority.tools.single { it.name == WorkspaceAuthority.APPLY_TOOL }
        assertEquals("object", JSONObject(apply.definition.inputSchemaJson).getString("type"))

        val create = call(
            apply.name,
            changes(
                JSONObject().put("operation", "create").put("path", "a.md").put("content", "A0\n"),
                JSONObject().put("operation", "create").put("path", "b.md").put("content", "B0\n"),
            ),
        )
        val createPlan = apply.prepare(create, authority.scopeId)
        assertTrue(createPlan.approvalPreview?.diff?.contains("+++ a.md") == true)
        val recovery = requireNotNull(apply.recoveryPayload(createPlan))
        assertTrue(apply.execute(createPlan) is ToolResult.Success)
        assertEquals("A0\n", store.get("a.md")?.content)
        assertEquals("B0\n", store.get("b.md")?.content)

        val reconciled = apply.reconcile(
            MutationRecord(
                MutationRecordId(UUID.randomUUID().toString()),
                create.id,
                apply.name,
                authority.scopeId,
                createPlan.fingerprint,
                recovery,
                MutationState.UNKNOWN,
            ),
        )
        assertTrue(reconciled is ToolResult.Success)

        val aHash = requireNotNull(store.get("a.md")).sha256
        val bHash = requireNotNull(store.get("b.md")).sha256
        val replace = call(
            apply.name,
            changes(
                JSONObject().put("operation", "replace").put("documentId", store.encodeId("a.md"))
                    .put("expectedSha256", aHash).put("content", "A1\n"),
                JSONObject().put("operation", "replace").put("documentId", store.encodeId("b.md"))
                    .put("expectedSha256", bHash).put("content", "B1\n"),
            ),
        )
        val replacePlan = apply.prepare(replace, authority.scopeId)
        store.apply(
            "intervening",
            listOf(WorkspaceChange("replace", "b.md", "B-other\n", bHash)),
        )
        assertTrue(apply.execute(replacePlan) is ToolResult.Rejected)
        assertEquals("A0\n", store.get("a.md")?.content)
        assertEquals("B-other\n", store.get("b.md")?.content)
    }

    @Test
    fun workspaceIdsAndCursorsFailClosed(): Unit = runBlocking {
        val store = WorkspaceStore(context)
        val authority = WorkspaceAuthority(store)
        store.apply("seed", listOf(WorkspaceChange("create", "notes.txt", "x".repeat(70_000), null)))
        val read = authority.tools.single { it.name == WorkspaceAuthority.READ_TOOL }
        val first = read.execute(
            read.prepare(
                call(read.name, JSONObject().put("documentId", store.encodeId("notes.txt")).toString()),
                authority.scopeId,
            ),
        ) as ToolResult.Success
        val cursor = JSONObject(first.outputJson).getString("nextCursor")
        val second = read.execute(
            read.prepare(
                call(
                    read.name,
                    JSONObject().put("documentId", store.encodeId("notes.txt")).put("cursor", cursor)
                        .toString(),
                ),
                authority.scopeId,
            ),
        ) as ToolResult.Success
        assertEquals(70_000 - WorkspaceAuthority.MAX_READ_CHARS, JSONObject(second.outputJson).getString("text").length)

        val forged = cursor.dropLast(1) + if (cursor.last() == 'A') 'B' else 'A'
        val rejected = runCatching {
            read.prepare(
                call(
                    read.name,
                    JSONObject().put("documentId", store.encodeId("notes.txt")).put("cursor", forged)
                        .toString(),
                ),
                authority.scopeId,
            )
        }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun exportsCreateOverwriteAndRejectStaleDestinations(): Unit = runBlocking {
        val store = WorkspaceStore(context)
        store.apply("seed", listOf(WorkspaceChange("create", "summary.md", "First\n", null)))
        val exportAuthority = WorkspaceExportAuthority(context, store)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Step02DocumentsProvider.AUTHORITY,
            Step02DocumentsProvider.ROOT_ID,
        )
        context.grantUriPermission(context.packageName, treeUri, EXPORT_GRANT_FLAGS)
        val scope = exportAuthority.persist(treeUri, null, false)
        val export = exportAuthority.tools.single()

        val create = call(
            export.name,
            JSONObject().put("workspaceDocumentId", store.encodeId("summary.md")).toString(),
        )
        val createPlan = export.prepare(create, scope)
        assertTrue(createPlan.approvalPreview?.diff?.contains("--- /dev/null") == true)
        val recovery = requireNotNull(export.recoveryPayload(createPlan))
        assertTrue(export.execute(createPlan) is ToolResult.Success)
        assertEquals("First\n", Step02DocumentsProvider.exportedText("summary.md"))
        assertTrue(
            export.reconcile(
                MutationRecord(
                    MutationRecordId(UUID.randomUUID().toString()),
                    create.id,
                    export.name,
                    scope,
                    createPlan.fingerprint,
                    recovery,
                    MutationState.UNKNOWN,
                ),
            ) is ToolResult.Success,
        )

        val first = requireNotNull(store.get("summary.md"))
        store.apply(
            "replace",
            listOf(WorkspaceChange("replace", "summary.md", "Second\n", first.sha256)),
        )
        val overwrite = call(
            export.name,
            JSONObject().put("workspaceDocumentId", store.encodeId("summary.md")).toString(),
        )
        val overwritePlan = export.prepare(overwrite, scope)
        assertTrue(overwritePlan.approvalPreview?.diff?.contains("-First") == true)
        assertTrue(export.execute(overwritePlan) is ToolResult.Success)
        assertEquals("Second\n", Step02DocumentsProvider.exportedText("summary.md"))

        val stale = call(
            export.name,
            JSONObject().put("workspaceDocumentId", store.encodeId("summary.md")).toString(),
        )
        val stalePlan = export.prepare(stale, scope)
        Step02DocumentsProvider.replaceExportText("summary.md", "Changed elsewhere\n")
        assertTrue(export.execute(stalePlan) is ToolResult.Rejected)
        assertEquals("Changed elsewhere\n", Step02DocumentsProvider.exportedText("summary.md"))
    }

    private fun changes(vararg changes: JSONObject): String =
        JSONObject().put("changes", JSONArray(changes.toList())).toString()

    private fun call(name: String, arguments: String) = ToolCall(
        ToolCallId(MessageDigest.getInstance("SHA-256").digest(arguments.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }),
        name,
        arguments,
    )

    private companion object {
        const val EXPORT_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
