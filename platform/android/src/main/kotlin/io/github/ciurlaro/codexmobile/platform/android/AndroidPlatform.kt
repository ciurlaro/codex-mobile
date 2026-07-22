package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import java.io.File

class AndroidPlatform internal constructor(
    context: Context,
    private val runtimeOverride: File?,
) {
    constructor(context: Context) : this(context, null)

    private val appContext = context.applicationContext
    private val workspace = WorkspaceManager(appContext)
    private val runtimeTools = RuntimeToolBundle(appContext)
    val skillPackages = AndroidSkillPackageManager(appContext, runtimeTools)
    private val telegram = TelegramCliIntegration(runtimeTools)

    fun launchCodexProcess(): Process {
        val runtime = runtimeOverride ?: File(appContext.applicationInfo.nativeLibraryDir, RUNTIME_FILE)
        check(runtime.isFile && runtime.canExecute()) { "Bundled Codex runtime is missing or not executable" }

        val codexHome = File(appContext.noBackupFilesDir, "codex").requireDirectory()
        val home = File(appContext.filesDir, "home").requireDirectory()
        val certificateBundle = prepareCertificateBundle(codexHome)
        val toolEnvironment = runtimeTools.prepareEnvironment(codexHome)
        val logsDatabase = File(codexHome, LOGS_DATABASE_FILE)
        sanitizeExistingRuntimeLogs(logsDatabase)
        val proxy = LoopbackConnectProxy()
        var started: Process? = null
        return try {
            val process = ProcessBuilder(runtime.absolutePath)
                .directory(home)
                .redirectErrorStream(false)
                .apply {
                    environment().putAll(toolEnvironment)
                    environment()["CODEX_HOME"] = codexHome.absolutePath
                    environment()["CODEX_SQLITE_HOME"] = codexHome.absolutePath
                    environment()["HOME"] = home.absolutePath
                    environment()["TMPDIR"] = appContext.cacheDir.absolutePath
                    environment()["SSL_CERT_FILE"] = certificateBundle.absolutePath
                    environment()["HTTPS_PROXY"] = proxy.url
                    environment()["https_proxy"] = proxy.url
                    environment()["NO_COLOR"] = "1"
                }
                .start()
            started = process
            awaitRuntimeLogPrivacyGuard(logsDatabase, process)
            ProxyBackedProcess(process, proxy)
        } catch (error: Exception) {
            started?.destroyForcibly()
            proxy.close()
            throw error
        }
    }

    fun hasStoragePermission(): Boolean = workspace.hasStoragePermission()

    fun configuredWorkspacePath(): String? =
        workspace.activeWorkspace()?.path ?: workspace.configuredPath()

    fun activeWorkspacePath(): String? = workspace.activeWorkspace()?.path

    fun workspaceRoots(): List<String> = workspace.roots().map(File::getPath)

    fun workspaceDirectories(path: String?): List<String> = workspace.directories(path).map(File::getPath)

    fun workspaceParent(path: String): String? = workspace.parent(path)?.path

    fun selectWorkspace(path: String): String = workspace.select(path).path

    fun clearWorkspace() = workspace.clear()

    fun telegramAvailable(): Boolean = telegram.available

    fun telegramStatus(): TelegramStatus = telegram.status()

    fun startTelegramAuthentication(phoneNumber: String): TelegramAuthSession =
        telegram.startAuthentication(phoneNumber)

    fun disconnectTelegram(): Boolean = telegram.disconnect()

    private fun File.requireDirectory(): File = apply {
        check(isDirectory || mkdirs()) { "Unable to prepare private runtime directory" }
    }

    private fun prepareCertificateBundle(codexHome: File): File = synchronized(CERTIFICATE_LOCK) {
        val certificates = File(SYSTEM_CERTIFICATE_DIRECTORY)
            .listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        check(certificates.isNotEmpty()) { "Android system certificates are unavailable" }

        File(codexHome, "android-system-ca.pem").apply {
            outputStream().buffered().use { output ->
                certificates.forEach { certificate ->
                    certificate.inputStream().use { it.copyTo(output) }
                    output.write('\n'.code)
                }
            }
            check(length() > 0) { "Unable to prepare Android system certificates" }
        }
    }

    private fun sanitizeExistingRuntimeLogs(databaseFile: File) {
        if (!databaseFile.isFile) return
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            installRuntimeLogPrivacyGuard(database)
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            database.execSQL("VACUUM")
        }
    }

    private fun awaitRuntimeLogPrivacyGuard(databaseFile: File, process: Process) {
        val deadline = SystemClock.elapsedRealtime() + LOG_DATABASE_TIMEOUT_MILLIS
        var lastFailure: SQLiteException? = null
        while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
            if (databaseFile.isFile) {
                try {
                    SQLiteDatabase.openDatabase(
                        databaseFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    ).use(::installRuntimeLogPrivacyGuard)
                    return
                } catch (error: SQLiteException) {
                    lastFailure = error
                }
            }
            SystemClock.sleep(LOG_DATABASE_RETRY_MILLIS)
        }
        throw IllegalStateException("Unable to prepare the private Codex log store", lastFailure)
    }

    private companion object {
        const val RUNTIME_FILE = "libcodex_app_server.so"
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val LOG_DATABASE_TIMEOUT_MILLIS = 20_000L
        const val LOG_DATABASE_RETRY_MILLIS = 25L
        const val SYSTEM_CERTIFICATE_DIRECTORY = "/system/etc/security/cacerts"
        val CERTIFICATE_LOCK = Any()
    }
}

internal fun installRuntimeLogPrivacyGuard(database: SQLiteDatabase) {
    database.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    database.beginTransaction()
    try {
        database.delete("logs", null, null)
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS codex_mobile_drop_runtime_logs
            BEFORE INSERT ON logs
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
}
