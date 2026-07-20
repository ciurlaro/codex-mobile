package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Step05BackgroundLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as CodexMobileApplication
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun explicitStartCreatesOneServiceAndHandlesNotificationPermission() {
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startFromVisibleActivity(scenario)
            val first = bind()
            try {
                assertTrue(first.binder.isForegroundStarted)
                assertEquals(1, activeNotifications().size)

                startFromVisibleActivity(scenario)
                bind().use { duplicate ->
                    assertEquals(first.binder.serviceInstanceId, duplicate.binder.serviceInstanceId)
                    assertSame(first.binder.controller, duplicate.binder.controller)
                }

                scenario.onActivity { activity ->
                    activity.startForegroundService(
                        Intent(activity, CodexForegroundService::class.java)
                            .setAction(CodexForegroundService.ACTION_START)
                            .putExtra(CodexForegroundService.EXTRA_AUTHORIZATION, "unauthorized"),
                    )
                }
                SystemClock.sleep(100)
                assertEquals(first.binder.serviceInstanceId, bind().use { it.binder.serviceInstanceId })
                assertEquals(1, activeNotifications().size)
            } finally {
                first.close()
            }
        } finally {
            scenario.close()
            cleanup()
        }

        shell(
            "am start-foreground-service -n ${context.packageName}/.app.CodexForegroundService " +
                "-a ${CodexForegroundService.ACTION_START}",
        )
        SystemClock.sleep(100)
        assertFalse(application.graph.wasBackgroundActive())
        assertTrue(activeNotifications().isEmpty())
        cleanup()
    }

    @Test
    fun notificationIsAccuratePrivateAndStopsWork() {
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startFromVisibleActivity(scenario)
            val notification = activeNotifications().single().notification
            assertEquals("Codex Mobile", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Starting background session", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(1, notification.actions.size)
            assertEquals("Stop", notification.actions.single().title.toString())
            assertFalse(notification.extras.toString().contains("prompt", ignoreCase = true))
            assertFalse(notification.extras.toString().contains("document", ignoreCase = true))

            bind().use { bound ->
                notification.actions.single().actionIntent.send()
                await { bound.binder.controller.state.value.terminal }
                await { activeNotifications().isEmpty() }
                assertFalse(application.graph.wasBackgroundActive())
            }
        } finally {
            scenario.close()
            cleanup()
        }
    }

    @Test
    fun bindingRecreationAndMultipleActivitiesKeepOneOwner() {
        ensureNotificationPermission()
        cleanup()
        val starter = ActivityScenario.launch(MainActivity::class.java)
        try {
            startFromVisibleActivity(starter)
            val expected = bind().use { it.binder.serviceInstanceId }

            val first = ActivityScenario.launch(MainActivity::class.java)
            try {
                val firstViewModel = first.viewModel()
                await { firstViewModel.serviceInstanceId != null }
                assertEquals(expected, firstViewModel.serviceInstanceId)

                bind().use { secondClient ->
                    bind().use { thirdClient ->
                        assertEquals(expected, secondClient.binder.serviceInstanceId)
                        assertEquals(expected, thirdClient.binder.serviceInstanceId)
                        assertSame(secondClient.binder.controller, thirdClient.binder.controller)
                    }
                }

                first.recreate()
                assertSame(firstViewModel, first.viewModel())
                assertEquals(expected, firstViewModel.serviceInstanceId)
                assertEquals(1, activeNotifications().size)
            } finally {
                first.close()
            }

            assertEquals(expected, bind().use { it.binder.serviceInstanceId })
        } finally {
            starter.close()
            cleanup()
        }
    }

    @Test
    fun homeScreenOffTaskRemovalAndActivityFinishKeepOneOwner() {
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        startFromVisibleActivity(scenario)
        val instance = bind().use { it.binder.serviceInstanceId }

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertEquals(instance, bind().use { it.binder.serviceInstanceId })

        val power = context.getSystemService(PowerManager::class.java)
        shell("input keyevent ${android.view.KeyEvent.KEYCODE_SLEEP}")
        try {
            await { !power.isInteractive }
            assertEquals(instance, bind().use { it.binder.serviceInstanceId })
            assertEquals(1, activeNotifications().size)
        } finally {
            shell("input keyevent ${android.view.KeyEvent.KEYCODE_WAKEUP}")
            shell("wm dismiss-keyguard")
            await { power.isInteractive }
        }

        scenario.onActivity { it.finishAndRemoveTask() }
        await { application.graph.wasBackgroundActive() }
        assertEquals(instance, bind().use { it.binder.serviceInstanceId })
        scenario.close()
        assertTrue(application.graph.wasBackgroundActive())
        assertEquals(1, activeNotifications().size)

        val services = packageInfo().services.orEmpty()
        val service = services.single { it.name == CodexForegroundService::class.java.name }
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertTrue(
            packageInfo().receivers.orEmpty()
                .none { it.exported },
        )
        cleanup()
    }

    @Test
    fun dataSyncTimeoutStopsWorkCleanly() {
        assumeTrue(
            "Run with a shortened Android dataSync timeout and -e step05Timeout true",
            InstrumentationRegistry.getArguments().getString("step05Timeout") == "true",
        )
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            startFromVisibleActivity(scenario)
            bind().use { bound ->
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                await(TIMEOUT_TEST_WAIT_MILLIS) { bound.binder.controller.state.value.terminal }
                assertTrue(
                    bound.binder.controller.state.value.status.contains("Android's time limit"),
                )
                assertFalse(application.graph.wasBackgroundActive())
                assertTrue(activeNotifications().isEmpty())
            }
        } finally {
            scenario.close()
            cleanup()
        }
    }

    @Test
    fun prolongedNetworkLossIsBoundedAndRetryable() {
        assumeTrue(
            "Run separately with -e step05NetworkLoss true",
            InstrumentationRegistry.getArguments().getString("step05NetworkLoss") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val wifiWasEnabled = shell("settings get global wifi_on").trim() == "1"
        var wifiRestored = !wifiWasEnabled
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            if (wifiWasEnabled) shell("svc wifi disable")
            SystemClock.sleep(1_000)
            val viewModel = scenario.viewModel()
            viewModel.authenticate()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

            bind().use { bound ->
                await(NETWORK_FAULT_WAIT_MILLIS) {
                    val state = bound.binder.controller.state.value
                    state.attentionRequired || state.verificationUrl != null || state.sessionId != null
                }
                val bounded = bound.binder.controller.state.value
                assertFalse(bounded.turnActive)
                assertTrue(bounded.status.length <= 500)
                assertTrue(bounded.streamedText.length < 263_000)
                assertTrue(
                    activeNotifications().single().notification.extras
                        .getString(Notification.EXTRA_TEXT) in setOf(
                            "Open Codex Mobile to retry",
                            "Waiting for ChatGPT sign-in",
                            "Session ready",
                        ),
                )
                assertTrue(application.graph.wasBackgroundActive())

                if (wifiWasEnabled) {
                    shell("svc wifi enable")
                    wifiRestored = true
                    SystemClock.sleep(NETWORK_RESTORE_WAIT_MILLIS)
                    if (bounded.verificationUrl != null) {
                        viewModel.cancelAuthentication()
                        await(NETWORK_RECOVERY_WAIT_MILLIS) {
                            viewModel.state.value.verificationUrl == null
                        }
                    }
                    if (viewModel.state.value.sessionId == null) viewModel.authenticate()
                    await(NETWORK_RECOVERY_WAIT_MILLIS) {
                        val state = viewModel.state.value
                        state.verificationUrl != null || state.sessionId != null
                    }
                    assertTrue(
                        activeNotifications().single().notification.extras
                            .getString(Notification.EXTRA_TEXT) != "Open Codex Mobile to retry",
                    )
                }
            }
        } finally {
            if (wifiWasEnabled && !wifiRestored) shell("svc wifi enable")
            scenario.close()
            cleanup()
        }
    }

    @Test
    fun sameDeviceBrowserHandoffKeepsOneForegroundOwner() {
        assumeTrue(
            "Run separately with -e step05BrowserHandoff true",
            InstrumentationRegistry.getArguments().getString("step05BrowserHandoff") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val viewModel = scenario.viewModel()
            viewModel.authenticate()
            bind().use { bound ->
                await(NETWORK_RECOVERY_WAIT_MILLIS) {
                    val state = bound.binder.controller.state.value
                    state.verificationUrl != null || state.sessionId != null
                }
                val verificationUrl = bound.binder.controller.state.value.verificationUrl
                assumeTrue(
                    "Browser handoff requires a fresh unauthenticated runtime",
                    verificationUrl != null,
                )
                val browserUri = Uri.parse(requireNotNull(verificationUrl))
                val instance = bound.binder.serviceInstanceId
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, browserUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                SystemClock.sleep(BROWSER_HANDOFF_WAIT_MILLIS)

                assertEquals(instance, bind().use { it.binder.serviceInstanceId })
                assertTrue(bound.binder.isForegroundStarted)
                assertTrue(application.graph.wasBackgroundActive())
                assertEquals(
                    "Waiting for ChatGPT sign-in",
                    activeNotifications().single().notification.extras.getString(Notification.EXTRA_TEXT),
                )
            }
        } finally {
            shell("input keyevent ${android.view.KeyEvent.KEYCODE_BACK}")
            scenario.close()
            cleanup()
        }
    }

    @Test
    fun profileHarnessHoldsLiveBackgroundRuntime() {
        assumeTrue(
            "Run separately with -e step05Profile true",
            InstrumentationRegistry.getArguments().getString("step05Profile") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.viewModel().authenticate()
            bind().use { bound ->
                await(NETWORK_RECOVERY_WAIT_MILLIS) {
                    val state = bound.binder.controller.state.value
                    state.verificationUrl != null || state.sessionId != null || state.attentionRequired
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertTrue(bound.binder.isForegroundStarted)
                assertEquals(1, activeNotifications().size)
                SystemClock.sleep(RESOURCE_PROFILE_WINDOW_MILLIS)
                assertTrue(bound.binder.isForegroundStarted)
                assertEquals(1, activeNotifications().size)
                assertTrue(bound.binder.controller.state.value.streamedText.length < 263_000)
            }
        } finally {
            scenario.close()
            cleanup()
        }
    }

    @Test
    fun notificationPermissionDeniedHasExplainedVisibleFallback() {
        assumeTrue(
            "Run in a separate process with notifications denied and -e step05NotificationDenied true",
            InstrumentationRegistry.getArguments().getString("step05NotificationDenied") == "true",
        )
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assertFalse(notifications.areNotificationsEnabled())
        cleanup()

        val starter = ActivityScenario.launch(MainActivity::class.java)
        startFromVisibleActivity(starter)
        starter.close()
        val visible = ActivityScenario.launch(MainActivity::class.java)
        try {
            val viewModel = visible.viewModel()
            await { viewModel.state.value.backgroundActive }
            assertFalse(viewModel.state.value.backgroundNotificationVisible)
            assertTrue(
                viewModel.state.value.backgroundActive &&
                    !viewModel.state.value.backgroundNotificationVisible,
            )
        } finally {
            visible.close()
            cleanup()
        }
    }

    @Test
    fun resourcesAndComponentExposureRemainBounded() {
        val packageInfo = packageInfo()
        val service = packageInfo.services.orEmpty().single {
            it.name == CodexForegroundService::class.java.name
        }
        assertTrue(service.enabled)
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission(
                    Manifest.permission.FOREGROUND_SERVICE,
                    context.packageName,
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission(
                    Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
                    context.packageName,
                ),
            )
        }
        assertTrue(
            packageInfo.receivers.orEmpty()
                .none { it.exported },
        )

        cleanup()
        val nullBinding = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) {
                nullBinding.countDown()
            }
        }
        assertTrue(
            context.bindService(
                Intent(context, CodexForegroundService::class.java).setAction("invalid"),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                assertTrue(nullBinding.await(5, TimeUnit.SECONDS))
            } else {
                SystemClock.sleep(100)
            }
            assertFalse(application.graph.wasBackgroundActive())
        } finally {
            context.unbindService(connection)
            cleanup()
        }
    }

    @Test
    fun prepareExternalLifecycleFault() {
        assumeTrue(
            "Run only with -e step05PrepareFault true before an external kill, force-stop, or reboot",
            InstrumentationRegistry.getArguments().getString("step05PrepareFault") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        startFromVisibleActivity(scenario)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(application.graph.wasBackgroundActive())
        assertEquals(1, activeNotifications().size)
        SystemClock.sleep(EXTERNAL_FAULT_WATCHDOG_MILLIS)
        throw AssertionError("Expected an external lifecycle fault")
    }

    @Test
    fun verifyExternalLifecycleFaultDidNotRestartWork() {
        assumeTrue(
            "Run only with -e step05VerifyFault true after the external lifecycle fault",
            InstrumentationRegistry.getArguments().getString("step05VerifyFault") == "true",
        )
        assertTrue(application.graph.wasBackgroundActive())
        assertTrue(activeNotifications().isEmpty())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val viewModel = scenario.viewModel()
            await { viewModel.state.value.status.contains("ended unexpectedly") }
            assertFalse(viewModel.state.value.backgroundActive)
        } finally {
            scenario.close()
            application.graph.markBackgroundActive(false)
        }
    }

    private fun startFromVisibleActivity(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val authorization = application.graph.authorizeForegroundStart()
            activity.startForegroundService(
                CodexForegroundService.startIntent(activity, authorization, authenticate = false),
            )
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (
            !application.graph.wasBackgroundActive() &&
            application.graph.backgroundFailure.value == null &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(25)
        }
        assertNull(application.graph.backgroundFailure.value)
        assertTrue(application.graph.wasBackgroundActive())
        if (notifications.areNotificationsEnabled()) await { activeNotifications().size == 1 }
    }

    private fun bind(): BoundService {
        val connected = CountDownLatch(1)
        lateinit var binder: CodexForegroundService.LocalBinder
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as CodexForegroundService.LocalBinder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                CodexForegroundService.bindIntent(context),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        return BoundService(binder, connection)
    }

    private fun ActivityScenario<MainActivity>.viewModel(): MainViewModel {
        lateinit var viewModel: MainViewModel
        onActivity { viewModel = ViewModelProvider(it)[MainViewModel::class.java] }
        return viewModel
    }

    private fun activeNotifications() = notifications.activeNotifications
        .filter { it.id == CodexForegroundService.NOTIFICATION_ID }

    @Suppress("DEPRECATION")
    private fun packageInfo() = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
    )

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notifications.areNotificationsEnabled()) return
        listOf(
            "pm grant --user current ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            "cmd appops set ${context.packageName} POST_NOTIFICATION allow",
        ).forEach(::shell)
        await { notifications.areNotificationsEnabled() }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun cleanup() {
        context.stopService(Intent(context, CodexForegroundService::class.java))
        application.graph.markBackgroundActive(false)
        await { activeNotifications().isEmpty() }
    }

    private fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue("Condition was not met before timeout", condition())
    }

    private inner class BoundService(
        val binder: CodexForegroundService.LocalBinder,
        private val connection: ServiceConnection,
    ) : AutoCloseable {
        override fun close() {
            context.unbindService(connection)
        }
    }

    private companion object {
        const val EXTERNAL_FAULT_WATCHDOG_MILLIS = 120_000L
        const val BROWSER_HANDOFF_WAIT_MILLIS = 5_000L
        const val NETWORK_FAULT_WAIT_MILLIS = 45_000L
        const val NETWORK_RECOVERY_WAIT_MILLIS = 60_000L
        const val NETWORK_RESTORE_WAIT_MILLIS = 5_000L
        const val RESOURCE_PROFILE_WINDOW_MILLIS = 120_000L
        const val TIMEOUT_TEST_WAIT_MILLIS = 20_000L
    }
}
