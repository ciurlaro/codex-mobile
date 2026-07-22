package io.github.ciurlaro.codexmobile.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

internal class WorkspaceManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun roots(): List<File> {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appContext.getSystemService(StorageManager::class.java).storageVolumes
                    .mapNotNullTo(this) { it.directory }
            }
            @Suppress("DEPRECATION")
            add(Environment.getExternalStorageDirectory())
        }
        return candidates.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .filter(File::isDirectory).distinctBy(File::getPath).sortedBy(File::getPath)
    }

    fun configuredPath(): String? = preferences.getString(PATH_KEY, null)

    fun activeWorkspace(): File? {
        if (!hasStoragePermission()) return null
        return configuredPath()?.let(::File)?.let { runCatching { requireAllowedDirectory(it) }.getOrNull() }
    }

    fun select(path: String): File {
        check(hasStoragePermission()) { "All-files access is required" }
        val directory = requireAllowedDirectory(File(path))
        check(preferences.edit().putString(PATH_KEY, directory.path).commit()) {
            "Unable to save the workspace"
        }
        return directory
    }

    fun clear() {
        check(preferences.edit().remove(PATH_KEY).commit()) { "Unable to clear the workspace" }
    }

    fun directories(path: String?): List<File> {
        check(hasStoragePermission()) { "All-files access is required" }
        if (path == null) return roots()
        return requireAllowedDirectory(File(path)).listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.canRead() }
            .mapNotNull { runCatching { requireAllowedDirectory(it) }.getOrNull() }
            .distinctBy(File::getPath)
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun parent(path: String): File? {
        val directory = requireAllowedDirectory(File(path))
        return directory.parentFile?.let { parent ->
            runCatching { requireAllowedDirectory(parent) }.getOrNull()
        }
    }

    private fun requireAllowedDirectory(value: File): File {
        val candidate = value.canonicalFile
        require(candidate.isDirectory && candidate.canRead()) { "Workspace directory is unavailable" }
        val root = roots().firstOrNull { candidate.isInside(it) }
            ?: throw SecurityException("Workspace is outside available shared storage")
        val relative = candidate.relativeTo(root).invariantSeparatorsPath.lowercase()
        require(relative != "android/data" && !relative.startsWith("android/data/") &&
            relative != "android/obb" && !relative.startsWith("android/obb/")) {
            "Android does not allow this workspace"
        }
        return candidate
    }

    private fun File.isInside(root: File): Boolean = path == root.path || path.startsWith(root.path + File.separator)

    private companion object {
        const val PREFERENCES = "local-workspace"
        const val PATH_KEY = "path"
    }
}
