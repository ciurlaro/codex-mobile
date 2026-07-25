package io.github.ciurlaro.codexmobile.appserver.host

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AppServerRuntimeDistributionTest {
    private val distribution = CodexMobileAppServerRuntime.DISTRIBUTION
    private val environment = RuntimeEnvironment(RuntimeKernel.LINUX, RuntimeArchitecture.AARCH64, true)

    @Test
    fun `accepts only the exact protocol identity and executable environment`() {
        distribution.requireCompatible(
            distribution.appServerVersion,
            distribution.upstreamRevision,
            distribution.schemaSha256,
            environment,
        )
        assertFailsWith<IllegalArgumentException> {
            distribution.requireCompatible("0.144.7", distribution.upstreamRevision, distribution.schemaSha256, environment)
        }
        assertFailsWith<IllegalArgumentException> {
            distribution.requireCompatible(
                distribution.appServerVersion,
                distribution.upstreamRevision,
                distribution.schemaSha256,
                environment.copy(supportsStaticElf = false),
            )
        }
    }
}
