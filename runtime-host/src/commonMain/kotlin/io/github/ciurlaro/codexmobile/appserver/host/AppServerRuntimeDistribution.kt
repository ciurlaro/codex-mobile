package io.github.ciurlaro.codexmobile.appserver.host

enum class RuntimeKernel { LINUX }

enum class RuntimeArchitecture { AARCH64 }

data class RuntimeEnvironment(
    val kernel: RuntimeKernel,
    val architecture: RuntimeArchitecture,
    val supportsStaticElf: Boolean,
)

data class AppServerRuntimeDistribution(
    val appServerVersion: String,
    val upstreamRevision: String,
    val schemaSha256: String,
    val targetTriple: String,
    val architecture: RuntimeArchitecture,
    val archiveSha256: String,
    val binarySha256: String,
) {
    init {
        require(appServerVersion.isNotBlank())
        require(upstreamRevision.matches(SHA256_OR_GIT_REVISION))
        require(schemaSha256.matches(SHA256))
        require(targetTriple.isNotBlank())
        require(archiveSha256.matches(SHA256))
        require(binarySha256.matches(SHA256))
    }

    fun requireCompatible(
        protocolVersion: String,
        protocolRevision: String,
        protocolSchemaSha256: String,
        environment: RuntimeEnvironment,
    ) {
        require(protocolVersion == appServerVersion) { "App Server client/runtime version mismatch" }
        require(protocolRevision == upstreamRevision) { "App Server client/runtime revision mismatch" }
        require(protocolSchemaSha256 == schemaSha256) { "App Server client/runtime schema mismatch" }
        require(environment.kernel == RuntimeKernel.LINUX) { "App Server runtime requires Linux" }
        require(environment.architecture == architecture) { "App Server runtime architecture mismatch" }
        require(environment.supportsStaticElf) { "App Server runtime requires static ELF execution" }
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        val SHA256_OR_GIT_REVISION = Regex("[a-f0-9]{40}|[a-f0-9]{64}")
    }
}

object CodexMobileAppServerRuntime {
    val DISTRIBUTION = AppServerRuntimeDistribution(
        appServerVersion = "0.144.6",
        upstreamRevision = "5d1fbf26c43abc65a203928b2e31561cb039e06d",
        schemaSha256 = "007e12d25541eb0a50bc778dfcff9e6ab88b3124c9425c4e8f79391d3538bec0",
        targetTriple = "aarch64-unknown-linux-musl",
        architecture = RuntimeArchitecture.AARCH64,
        archiveSha256 = "3539380f431aa72ce1e9ba83cf4d9b2c2a70d12ddf3280bc67c8c59f93bb9eb5",
        binarySha256 = "09d6a41d6189b14317ec5d556251e5195e9a4235c28867fc75ee5c1d54be02cd",
    )
}
