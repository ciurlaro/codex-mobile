package io.github.ciurlaro.codexmobile.extension.host

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidProviderRegistryDeviceTest {
    @Test
    fun documentsAndTelegramImplementTheRenamedProviderApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = AndroidProviderRegistry(context) { workspace, path, _ -> "$workspace/$path" }
        val descriptors = listOf(
            checkNotNull(registry.bundledProvider("documents@codex-mobile")).descriptor,
            checkNotNull(registry.bundledProvider("telegram@codex-mobile")).descriptor,
        )

        assertEquals(
            setOf("documents@codex-mobile", "telegram@codex-mobile"),
            descriptors.map { it.pluginId }.toSet(),
        )
        assertEquals(setOf(2), descriptors.map { it.providerApi }.toSet())
    }
}
