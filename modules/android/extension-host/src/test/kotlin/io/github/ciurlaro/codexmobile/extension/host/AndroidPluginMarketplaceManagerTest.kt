package io.github.ciurlaro.codexmobile.extension.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidPluginMarketplaceManagerTest {
    @Test
    fun `parses repository and tree URLs`() {
        assertEquals(
            GitHubMarketplaceLocation(GitHubRepository("owner", "plugins"), null, ""),
            GitHubMarketplaceLocation.parse("https://github.com/owner/plugins"),
        )
        assertEquals(
            GitHubMarketplaceLocation(GitHubRepository("owner", "plugins"), "release", "catalog/mobile"),
            GitHubMarketplaceLocation.parse("https://github.com/owner/plugins/tree/release/catalog/mobile"),
        )
        assertFailsWith<IllegalArgumentException> {
            GitHubMarketplaceLocation.parse("https://example.com/owner/plugins")
        }
    }

    @Test
    fun `extracts only the selected bounded marketplace tree`() {
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                mapOf(
                    "repo-main/catalog/mobile/.agents/plugins/marketplace.json" to "{}",
                    "repo-main/catalog/mobile/plugins/sample/.codex-plugin/plugin.json" to "{}",
                    "repo-main/outside.txt" to "outside",
                ).forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val destination = Files.createTempDirectory("marketplace-").toFile()
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use {
                extractMarketplaceArchive(it, "catalog/mobile", destination)
            }
            assertTrue(File(destination, ".agents/plugins/marketplace.json").isFile)
            assertTrue(File(destination, "plugins/sample/.codex-plugin/plugin.json").isFile)
            assertTrue(!File(destination, "outside.txt").exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `validates local plugin paths from the marketplace root`() {
        val marketplace = Files.createTempDirectory("marketplace-").toFile()
        try {
            File(marketplace, ".git").mkdirs()
            File(marketplace, ".git/config").writeText("[remote \"origin\"]\n")
            val manifest = File(marketplace, ".agents/plugins/marketplace.json")
            checkNotNull(manifest.parentFile).mkdirs()
            manifest.writeText(
                """{"plugins":[{"source":{"source":"local","path":"./catalog/plugins/sample"}}]}""",
            )
            assertTrue(!isValidMarketplaceSnapshot(marketplace))

            File(marketplace, "catalog/plugins/sample").mkdirs()
            assertTrue(isValidMarketplaceSnapshot(marketplace))
        } finally {
            marketplace.deleteRecursively()
        }
    }

    @Test
    fun `reads the marketplace identity used by the app server`() {
        val marketplace = Files.createTempDirectory("marketplace-name-").toFile()
        try {
            val manifest = File(marketplace, ".agents/plugins/marketplace.json")
            checkNotNull(manifest.parentFile).mkdirs()
            manifest.writeText("""{"name":"team-marketplace","plugins":[]}""")

            assertEquals("team-marketplace", readMarketplaceName(marketplace))
        } finally {
            marketplace.deleteRecursively()
        }
    }

    @Test
    fun `replaces a stale snapshot at the stable marketplace path`() {
        val root = Files.createTempDirectory("marketplaces-").toFile()
        try {
            val destination = File(root, "stable").apply {
                mkdirs()
                resolve("version").writeText("old")
            }
            val staging = File(root, ".install").apply {
                mkdirs()
                resolve("version").writeText("new")
            }

            replaceMarketplaceSnapshot(staging, destination)

            assertEquals("new", destination.resolve("version").readText())
            assertTrue(!staging.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".previous-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reuses a valid root snapshot from the same GitHub repository`() {
        val root = Files.createTempDirectory("marketplaces-").toFile()
        try {
            val snapshot = File(root, "snapshot").apply {
                File(this, ".git").mkdirs()
                File(this, ".git/config").writeText(
                    "[remote \"origin\"]\n\turl = https://github.com/Owner/Plugins.git\n",
                )
                val manifest = File(this, ".agents/plugins/marketplace.json")
                checkNotNull(manifest.parentFile).mkdirs()
                manifest.writeText("""{"name":"plugins","plugins":[]}""")
            }

            assertEquals(
                snapshot.canonicalFile,
                findReusableMarketplaceSnapshot(
                    root,
                    GitHubMarketplaceLocation.parse("https://github.com/owner/plugins"),
                )?.canonicalFile,
            )
            assertEquals(
                null,
                findReusableMarketplaceSnapshot(
                    root,
                    GitHubMarketplaceLocation.parse("https://github.com/owner/other"),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
