package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.os.Build
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexAppServerRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.RuntimeArchitecture
import io.github.ciurlaro.codexmobile.appserver.runtime.RuntimeEnvironment
import io.github.ciurlaro.codexmobile.appserver.runtime.RuntimeKernel
import java.io.File
import java.security.SecureRandom
import kotlinx.io.files.Path

internal class AndroidRuntimeBootstrap(
    context: Context,
    private val runtimeOverride: File?,
) {
    private val appContext = context.applicationContext

    fun create(): CodexRuntime {
        val activeAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val executable = runtimeOverride
            ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedBy(File::getName)
            .map { Path(it.absolutePath) }
        val platformEnvironment = buildMap {
            val path = listOfNotNull(
                System.getenv("PATH")?.takeIf(String::isNotBlank),
                ANDROID_SYSTEM_PATH,
            ).joinToString(":")
            put("PATH", path)
            put("LD_LIBRARY_PATH", appContext.applicationInfo.nativeLibraryDir)
            listOf("LANG", "LC_ALL", "TERM").forEach { name ->
                System.getenv(name)?.takeIf(String::isNotBlank)?.let { put(name, it) }
            }
        }
        return CodexAppServerRuntime(
            CodexRuntimeConfiguration(
                executable = Path(executable.absolutePath),
                packagedRuntimeEnvironment = if (runtimeOverride == null) {
                    RuntimeEnvironment(
                        kernel = RuntimeKernel.LINUX,
                        architecture = RuntimeArchitecture.AARCH64,
                        supportsStaticElf = activeAbi == "arm64-v8a",
                    )
                } else {
                    null
                },
                applicationDirectory = Path(File(appContext.filesDir, "home").absolutePath),
                privateDirectory = Path(appContext.noBackupFilesDir.absolutePath),
                temporaryDirectory = Path(appContext.cacheDir.absolutePath),
                certificateSources = certificates,
                sqliteDriver = AndroidSQLiteDriver(),
                platformEnvironment = platformEnvironment,
                proxyPassword = secureToken(),
            ),
        )
    }

    private fun secureToken(): String = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val RUNTIME_FILE = "libcodex_app_server.so"
        const val SYSTEM_CERTIFICATE_DIRECTORY = "/system/etc/security/cacerts"
        const val ANDROID_SYSTEM_PATH = "/system/bin:/system/xbin"
    }
}
