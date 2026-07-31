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
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.app.lifecycle.CodexMobileApplication
import io.github.ciurlaro.codexmobile.app.presentation.viewmodel.AppViewModel
import io.github.ciurlaro.codexmobile.app.session.background.CodexForegroundService
import io.github.ciurlaro.codexmobile.app.ui.shell.MainActivity
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

internal class ForegroundLifecycleDeviceTest : ForegroundLifecycleDeviceTestBase() {

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
            "am start-foreground-service -n ${context.packageName}/.app.session.background.CodexForegroundService " +
                "-a ${CodexForegroundService.ACTION_START}",
        )
        SystemClock.sleep(100)
        assertFalse(application.container.backgroundSessions.wasActive())
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
            assertEquals("Starting Codex", notification.extras.getString(Notification.EXTRA_TEXT))
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
                assertFalse(application.container.backgroundSessions.wasActive())
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
        await { application.container.backgroundSessions.wasActive() }
        assertEquals(instance, bind().use { it.binder.serviceInstanceId })
        scenario.close()
        assertTrue(application.container.backgroundSessions.wasActive())
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
            "Run with a shortened Android dataSync timeout and -e foregroundTimeout true",
            InstrumentationRegistry.getArguments().getString("foregroundTimeout") == "true",
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
                    bound.binder.controller.state.value.statusMessage.contains("Android's time limit"),
                )
                assertFalse(application.container.backgroundSessions.wasActive())
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
            "Run separately with -e networkLoss true",
            InstrumentationRegistry.getArguments().getString("networkLoss") == "true",
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
                    state.attentionRequired || state.signInUrl != null || state.sessionId != null
                }
                val bounded = bound.binder.controller.state.value
                assertFalse(bounded.isTurnActive)
                assertTrue(bounded.statusMessage.length <= 500)
                assertTrue(bounded.streamedText.length < 263_000)
                assertTrue(
                    activeNotifications().single().notification.extras
                        .getString(Notification.EXTRA_TEXT) in setOf(
                            "Open Codex Mobile to retry",
                            "Waiting for ChatGPT sign-in",
                            "Session ready",
                        ),
                )
                assertTrue(application.container.backgroundSessions.wasActive())

                if (wifiWasEnabled) {
                    shell("svc wifi enable")
                    wifiRestored = true
                    SystemClock.sleep(NETWORK_RESTORE_WAIT_MILLIS)
                    if (bounded.signInUrl != null) {
                        viewModel.cancelAuthentication()
                        await(NETWORK_RECOVERY_WAIT_MILLIS) {
                            viewModel.state.value.signInUrl == null
                        }
                    }
                    if (viewModel.state.value.sessionId == null) viewModel.authenticate()
                    await(NETWORK_RECOVERY_WAIT_MILLIS) {
                        val state = viewModel.state.value
                        state.signInUrl != null || state.sessionId != null
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

}
