package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.ApprovalRequirement
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolEffect
import io.github.ciurlaro.codexmobile.core.ToolExecutor
import io.github.ciurlaro.codexmobile.core.ToolRejectedException
import io.github.ciurlaro.codexmobile.core.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Step02ReadOnlyAuthorityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val callIds = AtomicInteger()

    @Before
    fun setUp() = clearScopeAndProvider()

    @After
    fun tearDown() = clearScopeAndProvider()

    @Test
    fun grantSelectionPersistenceRevocationAndIdentity(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        assertNull(platform.currentScopeId())

        val treeUri = grantTree()
        val first = platform.persistScope(treeUri)
        assertEquals(first, AndroidPlatform(context).currentScopeId())
        assertTrue(context.contentResolver.persistedUriPermissions.single().isReadPermission)
        assertFalse(context.contentResolver.persistedUriPermissions.single().isWritePermission)

        val list = platform.deviceTools().single { it.name == "list_documents" }
        assertSuspendFails<ToolRejectedException> {
            list.prepare(call(list.name, "{}"), ResourceScopeId("guessed"))
        }

        val second = platform.persistScope(treeUri)
        assertNotEquals(first, second)
        assertSuspendFails<ToolRejectedException> { list.prepare(call(list.name, "{}"), first) }

        context.getSharedPreferences("resource_scope", Context.MODE_PRIVATE).edit().clear().commit()
        assertNull(AndroidPlatform(context).currentScopeId())
        assertSuspendFails<ToolRejectedException> { list.prepare(call(list.name, "{}"), second) }

        val afterReset = platform.persistScope(treeUri)
        platform.revokeScope(afterReset)
        assertNull(platform.currentScopeId())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        context.revokeUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.grantUriPermission(context.packageName, treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertSuspendFails<SecurityException> { platform.persistScope(treeUri) }
    }

    @Test
    fun directoryListingHandlesNamesScaleAndProviderAnomalies(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val scope = platform.persistScope(grantTree())
        val root = list(platform, scope)
        val entries = root.getJSONArray("entries")
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
        assertEquals(names.sorted(), names)
        assertTrue(names.any { it.contains('/') && it.contains("😀") })
        assertTrue(names.any { it.length == 4_096 })
        repeat(entries.length()) { index ->
            val keys = entries.getJSONObject(index).keys().asSequence().toSet()
            assertTrue(keys == setOf("id", "name", "type") || keys == setOf("id", "name", "type", "sizeBytes"))
        }

        val empty = list(platform, scope, tokenFor(root, "empty"))
        assertEquals(0, empty.getInt("count"))
        val nested = list(platform, scope, tokenFor(root, "nested"))
        assertEquals(listOf(".dots..txt"), nested.entryNames())
        val large = list(platform, scope, tokenFor(root, "large"))
        assertEquals(300, large.getInt("count"))
        assertEquals(large.entryNames().sorted(), large.entryNames())

        listOf(
            Step02DocumentsProvider.Scenario.NULL_CHILDREN to "provider_null",
            Step02DocumentsProvider.Scenario.DUPLICATE to "provider_duplicate",
            Step02DocumentsProvider.Scenario.ESCAPE to "provider_escape",
            Step02DocumentsProvider.Scenario.SELF_CYCLE to "provider_cycle",
            Step02DocumentsProvider.Scenario.THROW_QUERY to "provider_null",
        ).forEach { (scenario, code) ->
            Step02DocumentsProvider.scenario = scenario
            val result = execute(platform, scope, "list_documents", "{}")
            assertEquals(code, assertResult<ToolResult.Failed>(result).code)
            Step02DocumentsProvider.reset()
        }
    }

    @Test
    fun boundedReadsHandleContentAndStreamFailures(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val scope = platform.persistScope(grantTree())
        val root = list(platform, scope)

        assertEquals("", read(platform, scope, tokenFor(root, "empty.txt")).getString("text"))
        assertEquals(
            "Grüezi 👋\n第二行",
            read(platform, scope, tokenFor(root, "עברית/emoji😀.txt")).getString("text"),
        )
        val misleading = read(platform, scope, tokenFor(root, "actually-text.pdf"))
        assertEquals("text", misleading.getString("format"))
        assertEquals("content wins over MIME", misleading.getString("text"))
        val docx = read(platform, scope, tokenFor(root, "sample.docx"))
        assertEquals("docx", docx.getString("format"))
        assertEquals("Hello DOCX", docx.getString("text"))

        val binary = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "binary.bin")).toString(),
        )
        assertEquals("unsupported_format", assertResult<ToolResult.Failed>(binary).code)

        val invalidUtf8 = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "invalid-utf8.txt")).toString(),
        )
        assertEquals("unsupported_format", assertResult<ToolResult.Failed>(invalidUtf8).code)

        val opensBeforeOversize = Step02DocumentsProvider.openCount()
        val oversized = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "oversized.txt")).toString(),
        )
        val oversizedOutput = JSONObject(assertResult<ToolResult.Success>(oversized).outputJson)
        assertTrue(oversizedOutput.has("nextCursor"))
        assertTrue(Step02DocumentsProvider.openCount() > opensBeforeOversize)

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.SHORT_READ
        val short = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "עברית/emoji😀.txt")).toString(),
        )
        assertEquals("size_mismatch", assertResult<ToolResult.Failed>(short).code)

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.STREAM_ERROR
        val streamFailure = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "empty.txt")).toString(),
        )
        assertEquals("io_failure", assertResult<ToolResult.Failed>(streamFailure).code)

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.THROW_OPEN
        val providerFailure = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "empty.txt")).toString(),
        )
        assertEquals("provider_failure", assertResult<ToolResult.Failed>(providerFailure).code)

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.DELETE_ON_OPEN
        val deleted = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "empty.txt")).toString(),
        )
        assertEquals("not_found", assertResult<ToolResult.Failed>(deleted).code)

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.CHANGING_METADATA
        val changed = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", tokenFor(root, "empty.txt")).toString(),
        )
        assertEquals("document_changed", assertResult<ToolResult.Failed>(changed).code)
    }

    @Test
    fun scopeConfinementAndFuzzedArgumentsFailClosedWithinBounds(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val treeUri = grantTree()
        val firstScope = platform.persistScope(treeUri)
        val root = list(platform, firstScope)
        val token = tokenFor(root, "empty.txt")
        val readTool = platform.deviceTools().single { it.name == "read_document" }

        listOf(
            "{\"documentId\":\"../outside\"}",
            "{\"documentId\":\"/storage/emulated/0/file\"}",
            "{\"documentId\":\"content://foreign/document/1\"}",
            "{\"documentId\":1}",
            "{\"documentId\":\"$token\",\"extra\":true}",
            "{\"documentId\":\"${"x".repeat(74 * 1_024)}\"}",
            "{}",
            "[]",
        ).forEach { arguments ->
            assertSuspendFails<ToolRejectedException> {
                readTool.prepare(call(readTool.name, arguments), firstScope)
            }
        }

        val random = Random(6)
        val fuzzStartedAt = SystemClock.elapsedRealtime()
        repeat(512) { index ->
            val length = random.nextInt(0, 2_048)
            val value = buildString(length) {
                repeat(length) { append(random.nextInt(0x20, 0x7f).toChar()) }
            }
            val arguments = when (index % 4) {
                0 -> JSONObject().put("unknown-$index", value).toString()
                1 -> JSONObject().put("documentId", index).toString()
                2 -> "{\"documentId\":\"$value"
                else -> "[$index,${JSONObject.quote(value)}]"
            }
            assertSuspendFails<ToolRejectedException> {
                readTool.prepare(call(readTool.name, arguments), firstScope)
            }
        }
        assertTrue(SystemClock.elapsedRealtime() - fuzzStartedAt < 10_000)

        val secondScope = platform.persistScope(treeUri)
        assertSuspendFails<ToolRejectedException> {
            readTool.prepare(
                call(readTool.name, JSONObject().put("documentId", token).toString()),
                secondScope,
            )
        }

        Step02DocumentsProvider.scenario = Step02DocumentsProvider.Scenario.ESCAPE
        val escaped = execute(platform, secondScope, "list_documents", "{}")
        assertEquals("provider_escape", assertResult<ToolResult.Failed>(escaped).code)
    }

    @Test
    fun toolValidationDefaultsToDenyAndReturnsAndroidTruth(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val scope = platform.persistScope(grantTree())
        val tools = platform.deviceTools()
        assertEquals(
            setOf(
                "list_documents",
                "read_document",
                WorkspaceAuthority.LIST_TOOL,
                WorkspaceAuthority.READ_TOOL,
            ),
            tools.filter { it.effect == ToolEffect.READ }.map { it.name }.toSet(),
        )
        val rename = tools.single { it.name == "rename_document" }
        assertEquals(ToolEffect.MUTATION, rename.effect)
        assertSuspendFails<ToolRejectedException> {
            rename.prepare(call(rename.name, "{}"), scope)
        }

        val executor = ToolExecutor(tools) { plan ->
            if (plan.effect == ToolEffect.READ) ApprovalRequirement.ALLOW else ApprovalRequirement.DENY
        }
        assertSuspendFails<ToolRejectedException> {
            executor.prepare(call("unknown", "{}"), scope)
        }
        val planned = executor.prepare(call("list_documents", "{}"), scope)
        platform.revokeScope(scope)
        assertResult<ToolResult.Rejected>(executor.execute(planned))
    }

    @Test
    fun providerLifecycleAndLoggingRemainSafe(): Unit = runBlocking {
        val platform = AndroidPlatform(context)
        val scope = platform.persistScope(grantTree())
        val root = list(platform, scope)
        read(platform, scope, tokenFor(root, "עברית/emoji😀.txt"))

        assertEquals(scope, AndroidPlatform(context).currentScopeId())
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "logcat -d --pid=${Process.myPid()} -t 500",
        )
        val logs = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().decodeToString()
        }
        assertFalse(logs.contains(TREE_URI.toString()))
        assertFalse(logs.contains("Grüezi"))
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
        context.revokeUriPermission(TREE_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun list(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        directoryId: String? = null,
    ): JSONObject {
        val arguments = JSONObject().apply { directoryId?.let { put("directoryId", it) } }.toString()
        val result = execute(platform, scope, "list_documents", arguments)
        return JSONObject(assertResult<ToolResult.Success>(result).outputJson)
    }

    private suspend fun read(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        documentId: String,
    ): JSONObject {
        val result = execute(
            platform,
            scope,
            "read_document",
            JSONObject().put("documentId", documentId).toString(),
        )
        return JSONObject(assertResult<ToolResult.Success>(result).outputJson)
    }

    private suspend fun execute(
        platform: AndroidPlatform,
        scope: ResourceScopeId,
        name: String,
        arguments: String,
    ): ToolResult {
        val tool = platform.deviceTools().single { it.name == name }
        return tool.execute(tool.prepare(call(name, arguments), scope))
    }

    private fun call(name: String, arguments: String) =
        ToolCall(ToolCallId(callIds.incrementAndGet().toString()), name, arguments)

    private fun tokenFor(list: JSONObject, name: String): String {
        val entries = list.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") == name) return entry.getString("id")
        }
        error("Missing test document")
    }

    private fun JSONObject.entryNames(): List<String> = getJSONArray("entries").let { entries ->
        (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
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
        assertTrue("Expected ${T::class.java.name}, got $value", value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(
            Step02DocumentsProvider.AUTHORITY,
            Step02DocumentsProvider.ROOT_ID,
        )
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
