package io.github.ciurlaro.codexmobile.app.security.navigation

import android.net.Uri

internal fun String.toOfficialSignInUri(): Uri? = runCatching { Uri.parse(this) }
    .getOrNull()
    ?.takeIf { uri ->
        val host = uri.host?.lowercase()
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && host != null &&
            (host == "openai.com" || host.endsWith(".openai.com") ||
                host == "chatgpt.com" || host.endsWith(".chatgpt.com"))
    }
