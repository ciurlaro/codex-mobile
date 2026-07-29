package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.os.Build
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexAppServerRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import java.io.File
import java.security.SecureRandom
import kotlinx.io.files.Path

internal class AndroidRuntimeBootstrap(
    context: Context,
    private val runtimeOverride: File?,
) {
    private val appContext = context.applicationContext

    fun create(): CodexRuntime {
        val executable = runtimeOverride
            ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedBy(File::getName)
            .map { Path(it.absolutePath) }
        return CodexAppServerRuntime(
            CodexRuntimeConfiguration(
                executable = Path(executable.absolutePath),
                verifyPackagedExecutable = runtimeOverride == null,
                applicationDirectory = Path(File(appContext.filesDir, "home").absolutePath),
                privateDirectory = Path(appContext.noBackupFilesDir.absolutePath),
                temporaryDirectory = Path(appContext.cacheDir.absolutePath),
                nativeLibraryDirectory = Path(appContext.applicationInfo.nativeLibraryDir),
                activeAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                certificateSources = certificates,
                sqliteDriver = AndroidSQLiteDriver(),
                inheritedEnvironment = listOf("PATH", "LANG", "LC_ALL", "TERM")
                    .mapNotNull { name -> System.getenv(name)?.let { name to it } }
                    .toMap(),
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
    }
}
