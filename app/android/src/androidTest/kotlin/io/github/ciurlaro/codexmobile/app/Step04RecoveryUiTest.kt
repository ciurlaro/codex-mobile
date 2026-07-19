package io.github.ciurlaro.codexmobile.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCallId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step04RecoveryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun unknownRecoveryRemainsVisibleUntilAcknowledged(): Unit = runBlocking {
        val application = instrumentation.targetContext.applicationContext as CodexMobileApplication
        val journal = application.graph.platform.mutationJournal()
        val id = MutationRecordId(UUID.randomUUID().toString())
        val record = MutationRecord(
            id,
            ToolCallId("step04-ui"),
            "rename_document",
            ResourceScopeId("unavailable-test-scope"),
            "step04-ui-fingerprint",
            "{}",
            MutationState.PREPARED,
        )
        journal.create(record)
        journal.transition(id, MutationState.PREPARED, MutationState.EXECUTING)
        journal.transition(id, MutationState.EXECUTING, MutationState.UNKNOWN)

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val notice = awaitNode("Android mutation outcome is unknown")
                assertFalse(notice.isClickable)
                val acknowledge = awaitNode("Acknowledge recovery").clickableParent()
                assertTrue(acknowledge.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitAbsent("Android mutation outcome is unknown")
            }
            assertTrue(journal.unresolved().any { it.id == id })
            assertFalse(journal.visible().any { it.id == id })
        } finally {
            if (journal.find(id)?.state == MutationState.UNKNOWN) {
                journal.transition(
                    id,
                    MutationState.UNKNOWN,
                    MutationState.FAILED,
                    "Disposable UI test record",
                    acknowledged = true,
                )
            }
            journal.pruneResolved(Long.MAX_VALUE)
        }
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.allNodes()
                ?.singleOrNull { it.text?.toString() == text }
                ?.let { return it }
            SystemClock.sleep(25)
        }
        error("Expected recovery UI was not visible")
    }

    private fun awaitAbsent(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val present = instrumentation.uiAutomation.rootInActiveWindow
                ?.allNodes()
                ?.any { it.text?.toString() == text } == true
            if (!present) return
            SystemClock.sleep(25)
        }
        error("Acknowledged recovery UI remained visible")
    }

    private fun AccessibilityNodeInfo.clickableParent(): AccessibilityNodeInfo {
        var current = this
        while (!current.isClickable) current = checkNotNull(current.parent)
        return current
    }

    private fun AccessibilityNodeInfo.allNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(this@allNodes)
    }
}
