package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteDriver
import kotlinx.io.files.Path

data class CodexRuntimeConfiguration(
    val executable: Path,
    val verifyPackagedExecutable: Boolean,
    val applicationDirectory: Path,
    val privateDirectory: Path,
    val temporaryDirectory: Path,
    val nativeLibraryDirectory: Path,
    val activeAbi: String,
    val certificateSources: List<Path>,
    val sqliteDriver: SQLiteDriver,
    val inheritedEnvironment: Map<String, String>,
    val proxyPassword: String,
)
