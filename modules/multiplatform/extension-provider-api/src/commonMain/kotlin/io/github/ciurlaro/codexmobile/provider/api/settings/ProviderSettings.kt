package io.github.ciurlaro.codexmobile.provider.api

data class ProviderSecretDefinition(
    val name: String,
    val displayName: String,
    val description: String? = null,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Provider secret name is invalid" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "Provider secret display name is invalid" }
        require(description == null || description.length <= 300) { "Provider secret description is too long" }
    }
}

fun interface ProviderSecrets {
    fun get(name: String): String?

    companion object {
        val EMPTY = ProviderSecrets { null }
    }
}
