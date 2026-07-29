package io.github.ciurlaro.codexmobile.provider.api

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.buildJsonObject

class ProviderDescriptorTest {
    @Test
    fun rejectsDuplicateToolNames() {
        val tool = ProviderToolDefinition("test@mobile", "read", "Read", buildJsonObject {})
        assertFailsWith<IllegalArgumentException> {
            ProviderDescriptor("test@mobile", "1", listOf(tool, tool), schemaDigest = "0".repeat(64))
        }
    }
}
