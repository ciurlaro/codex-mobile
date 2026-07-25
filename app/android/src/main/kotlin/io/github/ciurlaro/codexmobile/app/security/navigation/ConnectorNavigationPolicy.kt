package io.github.ciurlaro.codexmobile.app.security.navigation

import java.net.URI

internal fun isSafeConnectorNavigation(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() ||
        uri.scheme.equals("http", true) && (
            uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1"
            )
}.getOrDefault(false)
