package io.github.ciurlaro.codexmobile.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class ProviderPackageCallbacksTest {
    @Test
    fun `package completion describes installs and removals`() {
        assertEquals(
            "Documents installed",
            ProviderPackageCompletion(
                ProviderPackageOperationKind.INSTALL,
                "documents@codex-mobile",
                "Documents",
                successful = true,
            ).message,
        )
        assertEquals(
            "Documents removed",
            ProviderPackageCompletion(
                ProviderPackageOperationKind.REMOVE,
                "documents@codex-mobile",
                "Documents",
                successful = true,
            ).message,
        )
    }

    @Test
    fun `callback is consumed once`() = runBlocking {
        val callback = CompletableDeferred<Result<Unit>>()
        ProviderPackageCallbacks.register("install", callback)

        assertTrue(ProviderPackageCallbacks.complete("install", Result.success(Unit)))
        assertTrue(callback.await().isSuccess)
        assertFalse(ProviderPackageCallbacks.complete("install", Result.success(Unit)))
    }
}
