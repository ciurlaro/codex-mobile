package io.github.ciurlaro.codexmobile.extension.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import io.github.ciurlaro.codexmobile.agent.AgentSkill
import io.github.ciurlaro.codexmobile.agent.AgentSkillScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillPackageSafetyTest {
    @Test
    fun `accepts only explicit public GitHub tree folder URLs`() {
        val parsed = GitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example",
        )

        assertEquals("openai", parsed.owner)
        assertEquals("skills/.curated/example", parsed.path)
        assertEquals("", GitHubSkillLocation.parse("https://github.com/openai/skills/tree/main").path)
        listOf(
            "http://github.com/openai/skills/tree/main/skills/example",
            "https://example.com/openai/skills/tree/main/skills/example",
            "https://user@github.com/openai/skills/tree/main/skills/example",
            "https://github.com/openai/skills/blob/main/skills/example/SKILL.md",
            "https://github.com/openai/skills/tree/main/skills/../private",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { GitHubSkillLocation.parse(value) }
        }
    }

    @Test
    fun `discovers visible skill folders from a GitHub repository tree`() {
        val repository = GitHubRepository.parse("https://github.com/example/repository")
        val tree = listOf(
            GitHubTreeEntry("blob", "skills/one/SKILL.md"),
            GitHubTreeEntry("blob", "skills/two/SKILL.md"),
            GitHubTreeEntry("blob", ".mirror/skills/one/SKILL.md"),
            GitHubTreeEntry("blob", "SKILL.md"),
            GitHubTreeEntry("blob", "README.md"),
        )

        val locations = parseGitHubSkillTree(tree, repository, "main")

        assertEquals(listOf("", "skills/one", "skills/two"), locations.map { it.path })
        assertFailsWith<IllegalArgumentException> {
            GitHubRepository.parse("https://github.com/example/repository/tree/main/skills/one")
        }
    }

    @Test
    fun `extracts only requested subtree and requires root skill file`() {
        val output = Files.createTempDirectory("skill-extract-").toFile()
        try {
            extractSelectedSkill(
                zipOf(
                    "repo-main/skills/example/SKILL.md" to "---\nname: example\n---\n".encodeToByteArray(),
                    "repo-main/skills/example/references/a.txt" to "ok".encodeToByteArray(),
                    "repo-main/other/ignored.txt" to "ignored".encodeToByteArray(),
                ),
                "skills/example",
                output,
            )

            assertTrue(File(output, "SKILL.md").isFile)
            assertTrue(File(output, "references/a.txt").isFile)
            assertTrue(!File(output, "ignored.txt").exists())
        } finally {
            output.deleteRecursively()
        }

        val root = Files.createTempDirectory("skill-root-extract-").toFile()
        try {
            extractSelectedSkill(
                zipOf(
                    "repo-main/SKILL.md" to "---\nname: repository\n---\n".encodeToByteArray(),
                    "repo-main/reference.txt" to "ok".encodeToByteArray(),
                ),
                "",
                root,
            )
            assertTrue(File(root, "SKILL.md").isFile)
            assertTrue(File(root, "reference.txt").isFile)
        } finally {
            root.deleteRecursively()
        }

        val missing = Files.createTempDirectory("skill-missing-").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                extractSelectedSkill(
                    zipOf("repo-main/skills/example/readme.md" to byteArrayOf(1)),
                    "skills/example",
                    missing,
                )
            }
        } finally {
            missing.deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal duplicates invalid utf8 and bounded overflow`() {
        fun rejected(entries: List<Pair<String, ByteArray>>, maximumBytes: Long = 1024) {
            val output = Files.createTempDirectory("skill-reject-").toFile()
            try {
                assertFailsWith<Exception> {
                    extractSelectedSkill(zipOf(*entries.toTypedArray()), "skills/example", output, maximumBytes)
                }
            } finally {
                output.deleteRecursively()
            }
        }

        rejected(
            listOf(
                "repo-main/../escape" to byteArrayOf(1),
                "repo-main/skills/example/SKILL.md" to "ok".encodeToByteArray(),
            ),
        )
        rejected(
            listOf(
                "repo-main/skills/example/SKILL.md" to "ok".encodeToByteArray(),
                "repo-main/skills/example/value" to byteArrayOf(1),
                "repo-main/skills/example/value/" to byteArrayOf(),
            ),
        )
        rejected(listOf("repo-main/skills/example/SKILL.md" to byteArrayOf(0xC3.toByte(), 0x28)))
        rejected(listOf("repo-main/skills/example/SKILL.md" to "four".encodeToByteArray()), maximumBytes = 3)
    }

    @Test
    fun `only canonical top level user skill folders are removable`() {
        val root = Files.createTempDirectory("skill-root-").toFile()
        try {
            val source = File(root, "example/SKILL.md").apply {
                parentFile!!.mkdirs()
                writeText("---\nname: example\n---\n")
            }
            val skill = AgentSkill(
                name = "example",
                displayName = "Example",
                description = "",
                path = source.path,
                scope = AgentSkillScope.USER,
                enabled = true,
            )
            assertEquals(source.parentFile!!.canonicalFile, uninstallableSkillDirectory(skill, root))

            val nested = File(root, "plugin/example/SKILL.md").apply {
                parentFile!!.mkdirs()
                writeText("x")
            }
            assertFailsWith<IllegalArgumentException> {
                uninstallableSkillDirectory(skill.copy(path = nested.path), root)
            }
            val protected = File(root, ".system/SKILL.md").apply {
                parentFile!!.mkdirs()
                writeText("x")
            }
            assertFailsWith<IllegalArgumentException> {
                uninstallableSkillDirectory(skill.copy(path = protected.path), root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ZipInputStream {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return ZipInputStream(ByteArrayInputStream(bytes))
    }
}
