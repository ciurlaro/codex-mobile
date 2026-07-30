package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

internal fun buildMinimalRuntimeEnvironment(
    platform: Map<String, String>,
    applicationDirectory: Path,
    temporaryDirectory: Path,
    codexHome: Path,
    certificateBundle: Path,
    proxyUrl: String,
): Map<String, String> = buildMap {
    platform.forEach { (name, value) ->
        require(ENVIRONMENT_NAME.matches(name)) { "Invalid platform environment key: $name" }
        require('\u0000' !in value) { "Invalid platform environment value: $name" }
        require(name !in COMMON_ENVIRONMENT_KEYS) {
            "Platform environment cannot override common runtime key: $name"
        }
        if (value.isNotBlank()) put(name, value)
    }
    put("CODEX_HOME", codexHome.toString())
    put("CODEX_SQLITE_HOME", codexHome.toString())
    put("HOME", applicationDirectory.toString())
    put("TMPDIR", temporaryDirectory.toString())
    put("SSL_CERT_FILE", certificateBundle.toString())
    put("HTTPS_PROXY", proxyUrl)
    put("https_proxy", proxyUrl)
    put("NO_COLOR", "1")
}

private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val COMMON_ENVIRONMENT_KEYS = setOf(
    "CODEX_HOME",
    "CODEX_SQLITE_HOME",
    "HOME",
    "TMPDIR",
    "SSL_CERT_FILE",
    "HTTPS_PROXY",
    "https_proxy",
    "HTTP_PROXY",
    "http_proxy",
    "ALL_PROXY",
    "all_proxy",
    "NO_PROXY",
    "no_proxy",
    "SSL_CERT_DIR",
    "CURL_CA_BUNDLE",
    "REQUESTS_CA_BUNDLE",
    "NODE_EXTRA_CA_CERTS",
    "NO_COLOR",
)

internal fun prepareRuntimeCertificateBundle(certificateSources: List<Path>, codexHome: Path): Path {
    val certificates = certificateSources
        .filter(Path::isRegularFile)
        .sortedBy(Path::name)
    check(certificates.isNotEmpty()) { "System certificates are unavailable" }
    return Path(codexHome, "system-ca.pem").also { destination ->
        val output = SystemFileSystem.sink(destination).buffered()
        try {
            certificates.forEach { certificate ->
                val input = SystemFileSystem.source(certificate).buffered()
                try {
                    input.transferTo(output)
                } finally {
                    input.close()
                }
                output.write(byteArrayOf('\n'.code.toByte()))
            }
        } finally {
            output.close()
        }
        check(destination.isRegularFile() && SystemFileSystem.metadataOrNull(destination)!!.size > 0) {
            "Unable to prepare system certificates"
        }
    }
}
