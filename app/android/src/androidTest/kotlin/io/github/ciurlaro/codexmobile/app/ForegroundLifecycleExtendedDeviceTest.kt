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

internal class ForegroundLifecycleExtendedDeviceTest : ForegroundLifecycleDeviceTestBase() {
    @Test
    fun sameDeviceBrowserHandoffKeepsOneForegroundOwner() {
        assumeTrue(
            "Run separately with -e browserHandoff true",
            InstrumentationRegistry.getArguments().getString("browserHandoff") == "true",
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
                    state.signInUrl != null || state.sessionId != null
                }
                val signInUrl = bound.binder.controller.state.value.signInUrl
                assumeTrue(
                    "Browser handoff requires a fresh unauthenticated runtime",
                    signInUrl != null,
                )
                val instance = bound.binder.serviceInstanceId
                SystemClock.sleep(BROWSER_HANDOFF_WAIT_MILLIS)

                assertFalse(scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED)
                assertEquals(instance, bind().use { it.binder.serviceInstanceId })
                assertTrue(bound.binder.isForegroundStarted)
                assertTrue(application.container.backgroundSessions.wasActive())
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
            "Run separately with -e lifecycleProfile true",
            InstrumentationRegistry.getArguments().getString("lifecycleProfile") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.viewModel().authenticate()
            bind().use { bound ->
                await(NETWORK_RECOVERY_WAIT_MILLIS) {
                    val state = bound.binder.controller.state.value
                    state.signInUrl != null || state.sessionId != null || state.attentionRequired
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
            "Run in a separate process with notifications denied and -e notificationDenied true",
            InstrumentationRegistry.getArguments().getString("notificationDenied") == "true",
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
            await { viewModel.state.value.isBackgroundActive }
            assertFalse(viewModel.state.value.isBackgroundNotificationVisible)
            assertTrue(
                viewModel.state.value.isBackgroundActive &&
                    !viewModel.state.value.isBackgroundNotificationVisible,
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
            assertFalse(application.container.backgroundSessions.wasActive())
        } finally {
            context.unbindService(connection)
            cleanup()
        }
    }

    @Test
    fun prepareExternalLifecycleFault() {
        assumeTrue(
            "Run only with -e prepareLifecycleFault true before an external kill, force-stop, or reboot",
            InstrumentationRegistry.getArguments().getString("prepareLifecycleFault") == "true",
        )
        ensureNotificationPermission()
        cleanup()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        startFromVisibleActivity(scenario)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(application.container.backgroundSessions.wasActive())
        assertEquals(1, activeNotifications().size)
        SystemClock.sleep(EXTERNAL_FAULT_WATCHDOG_MILLIS)
        throw AssertionError("Expected an external lifecycle fault")
    }

    @Test
    fun verifyExternalLifecycleFaultDidNotRestartWork() {
        assumeTrue(
            "Run only with -e verifyLifecycleFault true after the external lifecycle fault",
            InstrumentationRegistry.getArguments().getString("verifyLifecycleFault") == "true",
        )
        assertTrue(application.container.backgroundSessions.wasActive())
        assertTrue(activeNotifications().isEmpty())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val viewModel = scenario.viewModel()
            await { viewModel.state.value.statusMessage.contains("ended unexpectedly") }
            assertFalse(viewModel.state.value.isBackgroundActive)
        } finally {
            scenario.close()
            application.container.backgroundSessions.markActive(false)
        }
    }

}
