package io.github.ciurlaro.codexmobile.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.app.ui.shell.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal class SessionBindingDeviceTest : ForegroundLifecycleDeviceTestBase() {
    @Test
    fun persistedAuthenticationUpgradesThePendingBinding(): Unit = runBlocking {
        assumeTrue(
            "Run credentialed service binding with -e authenticatedE2e true",
            InstrumentationRegistry.getArguments().getString("authenticatedE2e") == "true",
        )
        cleanup()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.viewModel()
            viewModel.authenticate()
            val authenticated = withTimeout(60_000) {
                viewModel.state.first {
                    it.isAuthenticated || it.statusMessage == "Background work ended"
                }
            }
            assertTrue(authenticated.statusMessage, authenticated.isAuthenticated)
            val serviceInstance = requireNotNull(viewModel.serviceInstanceId)

            SystemClock.sleep(1_500)

            assertEquals(serviceInstance, viewModel.serviceInstanceId)
            assertTrue(viewModel.state.value.isAuthenticated)
        }
        cleanup()
    }
}
