package io.github.ciurlaro.codexmobile.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

internal abstract class ForegroundLifecycleDeviceTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val application = context.applicationContext as CodexMobileApplication
    protected val notifications = context.getSystemService(NotificationManager::class.java)

    protected fun startFromVisibleActivity(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val authorization = application.container.backgroundSessions.authorizeStart()
            activity.startForegroundService(
                CodexForegroundService.startIntent(activity, authorization, authenticate = false),
            )
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (
            !application.container.backgroundSessions.wasActive() &&
            application.container.backgroundSessions.failure.value == null &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(25)
        }
        assertNull(application.container.backgroundSessions.failure.value)
        assertTrue(application.container.backgroundSessions.wasActive())
        if (notifications.areNotificationsEnabled()) await { activeNotifications().size == 1 }
    }

    protected fun bind(): BoundService {
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

    protected fun ActivityScenario<MainActivity>.viewModel(): AppViewModel {
        lateinit var viewModel: AppViewModel
        onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        return viewModel
    }

    protected fun activeNotifications() = notifications.activeNotifications
        .filter { it.id == CodexForegroundService.NOTIFICATION_ID }

    @Suppress("DEPRECATION")
    protected fun packageInfo() = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
    )

    protected fun ensureNotificationPermission() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notifications.areNotificationsEnabled()) return
        listOf(
            "pm grant --user current ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            "cmd appops set ${context.packageName} POST_NOTIFICATION allow",
        ).forEach(::shell)
        await { notifications.areNotificationsEnabled() }
    }

    protected fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    protected fun cleanup() {
        context.stopService(Intent(context, CodexForegroundService::class.java))
        application.container.backgroundSessions.markActive(false)
        await { activeNotifications().isEmpty() }
    }

    protected fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue("Condition was not met before timeout", condition())
    }

    protected inner class BoundService(
        val binder: CodexForegroundService.LocalBinder,
        private val connection: ServiceConnection,
    ) : AutoCloseable {
        override fun close() {
            context.unbindService(connection)
        }
    }

    protected companion object {
        const val EXTERNAL_FAULT_WATCHDOG_MILLIS = 120_000L
        const val BROWSER_HANDOFF_WAIT_MILLIS = 5_000L
        const val NETWORK_FAULT_WAIT_MILLIS = 45_000L
        const val NETWORK_RECOVERY_WAIT_MILLIS = 60_000L
        const val NETWORK_RESTORE_WAIT_MILLIS = 5_000L
        const val RESOURCE_PROFILE_WINDOW_MILLIS = 120_000L
        const val TIMEOUT_TEST_WAIT_MILLIS = 20_000L
    }
}
