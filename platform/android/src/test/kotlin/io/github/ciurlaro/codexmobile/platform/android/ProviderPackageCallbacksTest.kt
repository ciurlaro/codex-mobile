package io.github.ciurlaro.codexmobile.platform.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class ProviderPackageCallbacksTest {
    @Test
    fun `callback is consumed once`() = runBlocking {
        val callback = CompletableDeferred<Result<Unit>>()
        ProviderPackageCallbacks.register("install", callback)

        assertTrue(ProviderPackageCallbacks.complete("install", Result.success(Unit)))
        assertTrue(callback.await().isSuccess)
        assertFalse(ProviderPackageCallbacks.complete("install", Result.success(Unit)))
    }
}
