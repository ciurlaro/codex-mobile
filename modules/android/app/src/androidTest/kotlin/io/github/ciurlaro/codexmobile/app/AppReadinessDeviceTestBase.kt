package io.github.ciurlaro.codexmobile.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

abstract class AppReadinessDeviceTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext

    protected fun findNode(text: String): AccessibilityNodeInfo {
        repeat(100) {
            val nodes = flatten(root())
            val matches = nodes.filter { nodeLabel(it) == text }
            matches.firstOrNull()?.let { return it }
            val scrollable = nodes.firstOrNull { it.isScrollable }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                return@repeat
            }
            instrumentation.waitForIdleSync()
        }
        val activeRoot = root()
        throw AssertionError(
            "Accessibility node not found: $text; package=${activeRoot.packageName}; " +
                "class=${activeRoot.className}; nodes=${flatten(activeRoot).size}",
        )
    }

    protected fun findButton(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = findNode(text)
        repeat(5) {
            val candidate = node ?: return@repeat
            if (candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                assertTrue(flatten(candidate).any { it.className?.toString() == "android.widget.Button" })
                return candidate
            }
            node = candidate.parent
        }
        throw AssertionError("Accessible button action not found")
    }

    protected fun assertTouchTarget(label: String, minimumPixels: Float) {
        repeat(50) {
            val bounds = Rect().also(findButton(label)::getBoundsInScreen)
            if (bounds.width() >= minimumPixels && bounds.height() >= minimumPixels) return
            val scrollable = flatten(root()).firstOrNull { it.isScrollable }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                val metrics = context.resources.displayMetrics
                shell(
                    "input swipe ${metrics.widthPixels / 2} ${metrics.heightPixels * 3 / 4} " +
                        "${metrics.widthPixels / 2} ${metrics.heightPixels / 4} 250",
                )
            }
            instrumentation.waitForIdleSync()
        }
        throw AssertionError("$label could not be fully shown")
    }

    protected fun openSettings() {
        assertTrue(
            findButton("Open conversation history")
                .performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
        instrumentation.waitForIdleSync()
        assertTrue(findButton("Open Settings").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    protected fun scrollToStart() {
        repeat(100) {
            val scrollable = flatten(root()).firstOrNull { it.isScrollable } ?: return
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            instrumentation.waitForIdleSync()
        }
    }

    protected fun assertWindowContains(text: String) {
        repeat(50) {
            if (flatten(root()).any { nodeLabel(it)?.contains(text) == true }) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Accessibility window is missing required disclosure wording")
    }

    protected fun nodeLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString() ?: node.contentDescription?.toString()

    protected fun root(): AccessibilityNodeInfo {
        repeat(50) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("No active accessibility window")
    }

    protected fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
    }

    protected fun entryCount(path: String): Int = File(path).list().orEmpty().size

    protected fun wakeDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
    }

    protected fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
