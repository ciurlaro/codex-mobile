package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteDriver
import kotlinx.io.files.Path

data class CodexRuntimeConfiguration(
    val executable: Path,
    val packagedRuntimeEnvironment: RuntimeEnvironment?,
    val applicationDirectory: Path,
    val privateDirectory: Path,
    val temporaryDirectory: Path,
    val certificateSources: List<Path>,
    val sqliteDriver: SQLiteDriver,
    val platformEnvironment: Map<String, String>,
    val proxyPassword: String,
)
