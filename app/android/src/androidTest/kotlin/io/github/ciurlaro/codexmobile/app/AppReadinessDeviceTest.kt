package io.github.ciurlaro.codexmobile.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.agent.codex.CodexAgentClient
import io.github.ciurlaro.codexmobile.core.AgentEvent
import io.github.ciurlaro.codexmobile.platform.android.AndroidPlatform
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppReadinessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun manifestAuthorityBackupAndPrivateStorageFailClosed() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PERMISSIONS,
        )
        val applicationInfo = packageInfo.applicationInfo!!

        assertEquals(
            listOf(MainActivity::class.java.name),
            packageInfo.activities.orEmpty().filter { it.exported }.map { it.name },
        )
        assertTrue(packageInfo.services.orEmpty().none { it.exported })
        assertTrue(packageInfo.providers.orEmpty().none { it.exported })
        assertTrue(packageInfo.receivers.orEmpty().none { it.exported })
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.INTERNET",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ),
            packageInfo.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.toSet(),
        )
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir).forEach { directory ->
            assertTrue(directory.canonicalPath.startsWith(File(applicationInfo.dataDir).canonicalPath + "/"))
        }
    }

    @Test
    fun signInBrowserBoundaryAcceptsOnlyOfficialHttpsUrls() {
        assertNotNull("https://auth.openai.com/oauth/authorize?state=test".toOfficialSignInUri())
        assertNotNull("https://chatgpt.com/auth/login?state=test".toOfficialSignInUri())
        assertNull("http://auth.openai.com/oauth/authorize".toOfficialSignInUri())
        assertNull("https://openai.com.evil.example/oauth/authorize".toOfficialSignInUri())
        assertNull("https://user@auth.openai.com/oauth/authorize".toOfficialSignInUri())
        assertNull("https://auth.openai.com:8443/oauth/authorize".toOfficialSignInUri())
        assertNull("not a URL".toOfficialSignInUri())
    }

    @Test
    fun signedOutAccountRequiresFreshUserAuthorization(): Unit = runBlocking {
        assumeTrue(
            "Run only after the destructive sign-out phase with -e signedOut true",
            InstrumentationRegistry.getArguments().getString("signedOut") == "true",
        )
        CodexAgentClient(AndroidPlatform(context)::createCodexRuntime, 30_000).use { client ->
            val event = async { withTimeout(30_000) { client.events.first() } }
            client.authenticate()
            assertTrue(event.await() is AgentEvent.AuthenticationRequired)
            client.cancelAuthentication()
        }
    }

    @Test
    fun directAccountLogoutPersistsAcrossRuntimeRestart(): Unit = runBlocking {
        assumeTrue(
            "Run only in the destructive logout phase with -e directSignOut true",
            InstrumentationRegistry.getArguments().getString("directSignOut") == "true",
        )
        CodexAgentClient(AndroidPlatform(context)::createCodexRuntime, 30_000).use { it.signOut() }
        CodexAgentClient(AndroidPlatform(context)::createCodexRuntime, 30_000).use { client ->
            val event = async { withTimeout(30_000) { client.events.first() } }
            client.authenticate()
            assertTrue(event.await() is AgentEvent.AuthenticationRequired)
            client.cancelAuthentication()
        }
    }

    @Test
    fun accessibilityOrderRolesAnnouncementsAndDataWordingAreActionable() {
        wakeDevice()
        context.stopService(Intent(context, CodexForegroundService::class.java))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val title = findNode("Chat")
            val status = flatten(root()).first { it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }
            val statusText = status.text?.toString()
            assertTrue(!statusText.isNullOrBlank())
            val ordered = flatten(root()).mapNotNull(::nodeLabel)
            assertTrue(ordered.indexOf("Chat") < ordered.indexOf(statusText))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue(title.isHeading)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.liveRegion)

            openSettings()
            val settingsTitle = findNode("Settings")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue(settingsTitle.isHeading)
            val folderLabel = if (
                (context.applicationContext as CodexMobileApplication).graph.platform.configuredWorkspacePath() == null
            ) {
                "Select workspace"
            } else {
                "Change workspace"
            }
            val selectFolder = findButton(folderLabel)
            val signIn = findButton("Sign in with ChatGPT")
            assertTrue(selectFolder.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
            assertTrue(signIn.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

            scrollToStart()
            assertTrue(findButton("Privacy details").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertWindowContains("sent to OpenAI")
            assertWindowContains("starting folder")
            findButton("Close")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun dataErasureConfirmationIsExplicitAndNonDestructiveByDefault() {
        wakeDevice()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openSettings()
            scrollToStart()
            assertTrue(findButton("Erase Codex Mobile data").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertWindowContains("permanently erases")
            assertWindowContains("are not deleted")
            findButton("Keep data")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun largeFontReducedMotionAndTouchTargetsRemainUsable() {
        wakeDevice()
        val originalFontScale = shell("settings get system font_scale").trim().ifBlank { "1.0" }
        val originalAnimationScale = shell("settings get global animator_duration_scale").trim().ifBlank { "1.0" }
        shell("settings put system font_scale 2.0")
        shell("settings put global animator_duration_scale 0")
        SystemClock.sleep(500)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.waitForIdleSync()
            openSettings()
            scrollToStart()
            scenario.onActivity { activity -> assertTrue(activity.resources.configuration.fontScale >= 1.9f) }
            val minimumPixels = 48f * context.resources.displayMetrics.density - 1f
            val folderLabel = if (
                (context.applicationContext as CodexMobileApplication).graph.platform.configuredWorkspacePath() == null
            ) {
                "Select workspace"
            } else {
                "Change workspace"
            }
            listOf(
                folderLabel,
                "Privacy details",
                "Erase Codex Mobile data",
            ).forEach { label -> assertTouchTarget(label, minimumPixels) }
        } finally {
            scenario.close()
            shell("settings put system font_scale $originalFontScale")
            shell("settings put global animator_duration_scale $originalAnimationScale")
        }
    }

    @Test
    fun startupAndActivityStressStayWithinRecordedResourceBudgets() {
        context.stopService(Intent(context, CodexForegroundService::class.java))
        val startedAt = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.waitForIdleSync()
            val startupMillis = SystemClock.elapsedRealtime() - startedAt
            assertTrue("cold UI startup ${startupMillis}ms", startupMillis <= 5_000)
            repeat(20) { scenario.recreate(); instrumentation.waitForIdleSync() }
            Runtime.getRuntime().gc()
            SystemClock.sleep(500)
            val warmFds = entryCount("/proc/self/fd")
            val warmThreads = entryCount("/proc/self/task")
            val stressStartedAt = SystemClock.elapsedRealtime()

            repeat(20) { scenario.recreate(); instrumentation.waitForIdleSync() }

            Runtime.getRuntime().gc()
            SystemClock.sleep(500)
            val stressedFds = entryCount("/proc/self/fd")
            val stressedThreads = entryCount("/proc/self/task")

            assertTrue("Activity stress time", SystemClock.elapsedRealtime() - stressStartedAt <= 30_000)
            assertTrue("file descriptors before=$warmFds after=$stressedFds", stressedFds <= warmFds + 8)
            assertTrue("threads before=$warmThreads after=$stressedThreads", stressedThreads <= warmThreads + 8)
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val pssKiB = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                .single().totalPss
            assertTrue("PSS $pssKiB KiB", pssKiB <= 192 * 1024)
        } finally {
            scenario.close()
        }
    }

    private fun findNode(text: String): AccessibilityNodeInfo {
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

    private fun findButton(text: String): AccessibilityNodeInfo {
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

    private fun assertTouchTarget(label: String, minimumPixels: Float) {
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

    private fun openSettings() {
        assertTrue(
            findButton("Open conversation history")
                .performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
        instrumentation.waitForIdleSync()
        assertTrue(findButton("Open Settings").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollToStart() {
        repeat(100) {
            val scrollable = flatten(root()).firstOrNull { it.isScrollable } ?: return
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            instrumentation.waitForIdleSync()
        }
    }

    private fun assertWindowContains(text: String) {
        repeat(50) {
            if (flatten(root()).any { nodeLabel(it)?.contains(text) == true }) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Accessibility window is missing required disclosure wording")
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString() ?: node.contentDescription?.toString()

    private fun root(): AccessibilityNodeInfo {
        repeat(50) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("No active accessibility window")
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
    }

    private fun entryCount(path: String): Int = File(path).list().orEmpty().size

    private fun wakeDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
