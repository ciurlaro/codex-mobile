package io.github.ciurlaro.codexmobile.app

import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Step03ApprovalUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun reset() {
        Step03ApprovalTestActivity.approvals.set(0)
        Step03ApprovalTestActivity.denials.set(0)
    }

    @Test
    fun approvalUiIsSpoofResistantAndOneShotAcrossDenialAndLifecycle() {
        val scenario = ActivityScenario.launch(Step03ApprovalTestActivity::class.java)
        instrumentation.waitForIdleSync()
        var root = approvalRoot()
        val texts = root.allNodes().mapNotNull { it.text?.toString() }
        assertTrue("Approve Android change?" in texts)
        assertTrue(texts.any { it.startsWith("Operation: Rename document") })
        val source = texts.single { it.startsWith("Source: ") }
        assertTrue(source.contains("\\u{A}"))
        assertTrue(source.contains("\\u{202E}"))
        assertTrue(source.contains("\\u{2060}"))
        assertTrue(source.contains("\\u{2028}"))
        assertFalse(source.contains('\n'))
        assertFalse(source.contains('\u202e'))
        assertFalse(source.contains('\u2060'))
        assertFalse(source.contains('\u2028'))
        assertTrue(texts.any { it.startsWith("Destination: ") })
        assertTrue(texts.any { it == "Scope: Selected disposable folder" })
        assertTrue(texts.any { it.startsWith("Conflict behavior: Reject") })

        val approve = root.action("Approve once")
        assertTrue(approve.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        approve.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
        assertEquals(1, Step03ApprovalTestActivity.approvals.get())
        awaitApprovalAbsent()

        scenario.showApproval()
        root = approvalRoot()
        assertTrue(root.action("Deny").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(1, Step03ApprovalTestActivity.denials.get())
        assertEquals(1, Step03ApprovalTestActivity.approvals.get())
        awaitApprovalAbsent()

        scenario.showApproval()
        approvalRoot()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (Step03ApprovalTestActivity.denials.get() < 2 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertEquals(2, Step03ApprovalTestActivity.denials.get())
        assertEquals(1, Step03ApprovalTestActivity.approvals.get())
        awaitApprovalAbsent()

        scenario.showApproval()
        approvalRoot()
        scenario.recreate()
        instrumentation.waitForIdleSync()
        approvalRoot()
        assertEquals(2, Step03ApprovalTestActivity.denials.get())
        assertEquals(1, Step03ApprovalTestActivity.approvals.get())

        scenario.onActivity { it.finish() }
        instrumentation.waitForIdleSync()
        assertEquals(2, Step03ApprovalTestActivity.denials.get())
        assertEquals(1, Step03ApprovalTestActivity.approvals.get())
        scenario.close()
    }

    private fun ActivityScenario<Step03ApprovalTestActivity>.showApproval() {
        onActivity(Step03ApprovalTestActivity::showApproval)
        instrumentation.waitForIdleSync()
    }

    private fun AccessibilityNodeInfo.allNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(this@allNodes)
    }

    private fun approvalRoot(): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 2_000
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
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root == null || root.allNodes().none { it.text?.toString() == "Approve Android change?" }) return
            SystemClock.sleep(25)
        }
        error("Approval dialog did not close")
    }

    private fun AccessibilityNodeInfo.action(label: String): AccessibilityNodeInfo {
        var node = allNodes().single { it.text?.toString() == label }
        while (!node.isClickable) node = checkNotNull(node.parent)
        return node
    }
}
