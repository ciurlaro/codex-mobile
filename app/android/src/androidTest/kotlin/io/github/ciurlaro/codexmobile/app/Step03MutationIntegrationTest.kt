package io.github.ciurlaro.codexmobile.app

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.SessionId
import io.github.ciurlaro.codexmobile.core.ToolCall
import io.github.ciurlaro.codexmobile.core.ToolCallId
import io.github.ciurlaro.codexmobile.core.ToolResult
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class Step03MutationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun productionApprovalDispatchesExactlyOneObservedStockProviderRename(): Unit = runBlocking {
        requirePhysicalDevice()
        val fixture = fixture()
        assertNames(fixture, required = setOf(RENAME_SOURCE, UNTOUCHED), absent = setOf(RENAME_TARGET))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.viewModel()
            requestRename(viewModel, fixture, RENAME_SOURCE, RENAME_TARGET, "stock-approved")
            val root = approvalRoot()
            val approve = root.action("Approve once")
            assertTrue(approve.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            approve.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitApprovalClosed(viewModel)
            awaitApprovalAbsent()
            withTimeout(5_000) {
                while (RENAME_TARGET !in names(fixture)) delay(25)
            }
        }

        assertNames(fixture, required = setOf(RENAME_TARGET, UNTOUCHED), absent = setOf(RENAME_SOURCE))
    }

    @Test
    fun productionDenyDismissBackTimeoutAndDestructionNeverDispatch(): Unit = runBlocking {
        requirePhysicalDevice()
        val fixture = fixture()
        val attempted = mutableSetOf<String>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val viewModel = scenario.viewModel()

        requestRename(viewModel, fixture, GUARDED_SOURCE, "denied.txt", "stock-deny")
        attempted += "denied.txt"
        assertTrue(approvalRoot().action("Deny").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitApprovalClosed(viewModel)
        awaitApprovalAbsent()
        assertNames(fixture, required = setOf(GUARDED_SOURCE), absent = attempted)

        requestRename(viewModel, fixture, GUARDED_SOURCE, "dismissed.txt", "stock-dismiss")
        attempted += "dismissed.txt"
        tapOutsideDialog()
        awaitApprovalClosed(viewModel)
        awaitApprovalAbsent()
        assertNames(fixture, required = setOf(GUARDED_SOURCE), absent = attempted)

        requestRename(viewModel, fixture, GUARDED_SOURCE, "back.txt", "stock-back")
        attempted += "back.txt"
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitApprovalClosed(viewModel)
        awaitApprovalAbsent()
        assertNames(fixture, required = setOf(GUARDED_SOURCE), absent = attempted)

        val timeoutStarted = SystemClock.elapsedRealtime()
        requestRename(viewModel, fixture, GUARDED_SOURCE, "timeout.txt", "stock-timeout")
        attempted += "timeout.txt"
        withTimeout(35_000) {
            while (viewModel.state.value.approvalPreview != null) delay(25)
        }
        assertTrue(SystemClock.elapsedRealtime() - timeoutStarted >= 30_000)
        awaitApprovalAbsent()
        assertNames(fixture, required = setOf(GUARDED_SOURCE), absent = attempted)

        requestRename(viewModel, fixture, GUARDED_SOURCE, "destroyed.txt", "stock-destroy")
        attempted += "destroyed.txt"
        scenario.onActivity { it.finish() }
        instrumentation.waitForIdleSync()
        scenario.close()
        assertNames(fixture, required = setOf(GUARDED_SOURCE), absent = attempted)
    }

    @Test
    fun processDeathFaultHarness(): Unit = runBlocking {
        assumeTrue(
            "Run only with -e step03ProcessDeath true and force-stop the app after the ready marker",
            InstrumentationRegistry.getArguments().getString("step03ProcessDeath") == "true",
        )
        requirePhysicalDevice()
        val fixture = fixture()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val viewModel = scenario.viewModel()
        requestRename(viewModel, fixture, DEATH_SOURCE, DEATH_TARGET, "stock-process-death")
        Log.i(PROCESS_DEATH_TAG, PROCESS_DEATH_READY)
        delay(PROCESS_DEATH_WATCHDOG_MILLIS)
        scenario.close()
        throw AssertionError("Expected the external force-stop fault")
    }

    @Test
    fun disposableProcessDeathSourceIsUnchanged(): Unit = runBlocking {
        requirePhysicalDevice()
        assertNames(fixture(), required = setOf(DEATH_SOURCE), absent = setOf(DEATH_TARGET))
    }

    private suspend fun requestRename(
        viewModel: MainViewModel,
        fixture: Fixture,
        sourceName: String,
        destinationName: String,
        callId: String,
    ) {
        val token = tokenFor(list(fixture), sourceName)
        viewModel.reduce(
            AgentEvent.ToolRequested(
                SessionId("step03-physical-session"),
                ToolCall(
                    ToolCallId(callId),
                    "rename_document",
                    JSONObject()
                        .put("documentId", token)
                        .put("newName", destinationName)
                        .toString(),
                ),
            ),
        )
        withTimeout(5_000) {
            while (viewModel.state.value.approvalPreview == null) delay(25)
        }
    }

    private suspend fun awaitApprovalClosed(viewModel: MainViewModel) = withTimeout(5_000) {
        while (viewModel.state.value.approvalPreview != null) delay(25)
    }

    private suspend fun fixture(): Fixture {
        val platform = AndroidPlatform(context)
        val scope = requireNotNull(platform.currentScopeId()) {
            "Select the dedicated Step 03 disposable folder through DocumentsUI first"
        }
        check(platform.currentScopeAllowsMutations()) {
            "The dedicated Step 03 folder must have explicit mutation access"
        }
        return Fixture(platform, scope)
    }

    private suspend fun list(fixture: Fixture): JSONObject {
        val tool = fixture.platform.deviceTools().single { it.name == "list_documents" }
        val call = ToolCall(ToolCallId("step03-list"), tool.name, "{}")
        val result = tool.execute(tool.prepare(call, fixture.scope))
        check(result is ToolResult.Success) { "Unable to list the disposable stock-provider fixture" }
        return JSONObject(result.outputJson)
    }

    private suspend fun names(fixture: Fixture): Set<String> {
        val entries = list(fixture).getJSONArray("entries")
        return buildSet {
            repeat(entries.length()) { index -> add(entries.getJSONObject(index).getString("name")) }
        }
    }

    private suspend fun assertNames(fixture: Fixture, required: Set<String>, absent: Set<String>) {
        val names = names(fixture)
        assertTrue(names.containsAll(required))
        assertFalse(names.any(absent::contains))
    }

    private fun tokenFor(list: JSONObject, name: String): String {
        val entries = list.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") == name) return entry.getString("id")
        }
        error("Required disposable document is unavailable")
    }

    private fun ActivityScenario<MainActivity>.viewModel(): MainViewModel {
        lateinit var value: MainViewModel
        onActivity { value = ViewModelProvider(it)[MainViewModel::class.java] }
        return value
    }

    private fun tapOutsideDialog() {
        approvalRoot()
        val now = SystemClock.uptimeMillis()
        val y = context.resources.displayMetrics.heightPixels / 2f
        listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, y, 0),
            MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 1f, y, 0),
        ).forEach { event ->
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun approvalRoot(): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.allNodes().any { it.text?.toString() == "Approve Android change?" }) {
                return root
            }
            SystemClock.sleep(25)
        }
        error("Approval dialog did not become accessible")
    }

    private fun awaitApprovalAbsent() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root == null || root.allNodes().none { it.text?.toString() == "Approve Android change?" }) return
            SystemClock.sleep(25)
        }
        error("Approval dialog did not close")
    }

    private fun AccessibilityNodeInfo.allNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(this@allNodes)
    }

    private fun AccessibilityNodeInfo.action(label: String): AccessibilityNodeInfo {
        var node = allNodes().single { it.text?.toString() == label }
        while (!node.isClickable) node = checkNotNull(node.parent)
        return node
    }

    private fun requirePhysicalDevice() {
        assumeFalse(
            "Stock-provider mutation evidence requires a physical device",
            Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("Emulator"),
        )
    }

    private data class Fixture(val platform: AndroidPlatform, val scope: ResourceScopeId)

    private companion object {
        const val RENAME_SOURCE = "rename-before.txt"
        const val RENAME_TARGET = "rename-after.txt"
        const val GUARDED_SOURCE = "guarded.txt"
        const val DEATH_SOURCE = "death-before.txt"
        const val DEATH_TARGET = "death-after.txt"
        const val UNTOUCHED = "untouched.txt"
        const val PROCESS_DEATH_TAG = "CodexMobileStep03Death"
        const val PROCESS_DEATH_READY = "pending approval ready"
        const val PROCESS_DEATH_WATCHDOG_MILLIS = 120_000L
    }
}
