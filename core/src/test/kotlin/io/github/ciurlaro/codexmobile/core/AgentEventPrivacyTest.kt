package io.github.ciurlaro.codexmobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentEventPrivacyTest {
    @Test
    fun authenticationRequiredDoesNotRenderCredentials() {
        val event = AgentEvent.AuthenticationRequired(
            verificationUrl = "https://example.invalid/do-not-render",
            userCode = "do-not-render",
        )

        assertEquals("AuthenticationRequired", event.toString())
    }
}
