package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream

internal class BuiltInPluginBundle(context: Context) {
    private val appContext = context.applicationContext

    @Synchronized
    fun prepare(home: File) {
        val marketplace = File(home, ".agents/plugins/marketplace.json")
        copyAsset("codex/plugins/codex-mobile/marketplace.json", marketplace)
        PLUGIN_FILES.forEach { relative ->
            copyAsset(
                "codex/plugins/codex-mobile/plugins/$relative",
                File(home, "plugins/$relative"),
            )
        }
    }

    private fun copyAsset(asset: String, output: File) {
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val next = File(output.parentFile, ".${output.name}.next")
        appContext.assets.open(asset).use { input ->
            FileOutputStream(next).buffered().use { target -> input.copyTo(target) }
        }
        check(!output.exists() || output.delete()) { "Unable to replace built-in plugin file" }
        check(next.renameTo(output)) { "Unable to install built-in plugin file" }
    }

    private companion object {
        val PLUGIN_FILES = listOf(
            "documents/.codex-plugin/plugin.json",
            "documents/skills/documents/SKILL.md",
            "telegram/.codex-plugin/plugin.json",
            "telegram/skills/telegram/SKILL.md",
        )
    }
}
