package io.github.ciurlaro.codexmobile.extension.host

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidProviderSecretStoreDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun secretsAreEncryptedAndScopedPerPlugin() {
        val suffix = UUID.randomUUID().toString()
        val first = AndroidProviderSecretStore(context, "first-$suffix@catalog")
        val second = AndroidProviderSecretStore(context, "second-$suffix@catalog")
        val firstValue = "first-$suffix"
        val secondValue = "second-$suffix"
        try {
            first.replace(mapOf("token" to firstValue))
            second.replace(mapOf("token" to secondValue))

            assertEquals(firstValue, first.snapshot().get("token"))
            assertEquals(secondValue, second.snapshot().get("token"))
            val stored = context.noBackupFilesDir.resolve("provider-secrets")
                .listFiles().orEmpty().joinToString { it.readText() }
            assertFalse(stored.contains(firstValue))
            assertFalse(stored.contains(secondValue))

            first.clear()
            assertNull(first.snapshot().get("token"))
            assertEquals(secondValue, second.snapshot().get("token"))
        } finally {
            first.clear()
            second.clear()
        }
    }
}
