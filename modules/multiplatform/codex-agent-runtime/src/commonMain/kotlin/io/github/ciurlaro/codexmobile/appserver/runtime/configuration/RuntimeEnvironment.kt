package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

internal fun buildMinimalRuntimeEnvironment(
    inherited: Map<String, String>,
    applicationDirectory: Path,
    temporaryDirectory: Path,
    nativeLibraryDirectory: Path,
    codexHome: Path,
    certificateBundle: Path,
    proxyUrl: String,
): Map<String, String> = buildMap {
    put(
        "PATH",
        listOf(inherited["PATH"].orEmpty(), "/system/bin:/system/xbin")
            .filter(String::isNotBlank)
            .joinToString(":"),
    )
    put("LD_LIBRARY_PATH", nativeLibraryDirectory.toString())
    listOf("LANG", "LC_ALL", "TERM").forEach { name ->
        inherited[name]?.takeIf(String::isNotBlank)?.let { put(name, it) }
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
