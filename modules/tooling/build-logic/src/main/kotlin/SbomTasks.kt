import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@CacheableTask
abstract class UpdateSbomTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyLock: RegularFileProperty
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val codexVersion: Property<String>
    @get:Input abstract val archiveSha256: Property<String>
    @get:Input abstract val binarySha256: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun update() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(generateSbom())
        logger.lifecycle("wrote ${output.name} (${output.sha256()})")
    }
}

@DisableCachingByDefault(because = "Read-only verification has no reusable output")
abstract class VerifySbomTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyLock: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbom: RegularFileProperty
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val codexVersion: Property<String>
    @get:Input abstract val archiveSha256: Property<String>
    @get:Input abstract val binarySha256: Property<String>

    @TaskAction
    fun verify() {
        check(sbom.get().asFile.readText() == generateSbom()) {
            "${CodexMobileAutomation.Artifacts.SBOM} is stale; run ./gradlew ${CodexMobileAutomation.Tasks.UPDATE_SBOM}"
        }
    }
}

private fun Any.generateSbom(): String {
    val task = this
    val lock = when (task) {
        is UpdateSbomTask -> task.dependencyLock.get().asFile
        is VerifySbomTask -> task.dependencyLock.get().asFile
        else -> error("unsupported SBOM task")
    }
    fun property(selector: (UpdateSbomTask) -> Property<String>, verifier: (VerifySbomTask) -> Property<String>) =
        when (task) {
            is UpdateSbomTask -> selector(task).get()
            is VerifySbomTask -> verifier(task).get()
            else -> error("unsupported SBOM task")
        }
    val version = property(UpdateSbomTask::versionName, VerifySbomTask::versionName)
    val codexVersion = property(UpdateSbomTask::codexVersion, VerifySbomTask::codexVersion)
    val archiveHash = property(UpdateSbomTask::archiveSha256, VerifySbomTask::archiveSha256)
    val binaryHash = property(UpdateSbomTask::binarySha256, VerifySbomTask::binarySha256)
    require(archiveHash.matches(HASH)) { "invalid SBOM archive hash" }
    require(binaryHash.matches(HASH)) { "invalid SBOM binary hash" }
    val dependencies = lock.useLines { lines ->
        lines.filter { !it.startsWith("#") && "=" in it }
            .mapNotNull { line ->
                val (coordinate, configurations) = line.split("=", limit = 2)
                coordinate.takeIf { "releaseRuntimeClasspath" in configurations.split(",") }
            }
            .map { coordinate -> coordinate.split(":", limit = 3).also { require(it.size == 3) } }
            .distinct()
            .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
            .toList()
    }
    val appRef = "pkg:generic/codex-mobile@$version?platform=android"
    val internal = listOf(
        "codex-mobile-shared" to
            "Portable application state, persistence, session orchestration, and UI.",
        "codex-mobile-android-app" to
            "Android lifecycle, workspace, rendering, and packaging mechanisms.",
    ).map { (name, description) -> component(name, version, description) }
    val codexRef = "pkg:generic/openai/codex-app-server@$codexVersion?arch=arm64"
    val codex = sortedMapOf<String, Any>(
        "bom-ref" to codexRef,
        "description" to "Pinned standalone Codex protocol runtime and ordinary-shell owner.",
        "group" to "OpenAI",
        "hashes" to listOf(sortedMapOf("alg" to "SHA-256", "content" to binaryHash)),
        "licenses" to licenses("Apache-2.0"),
        "name" to "codex-app-server",
        "properties" to listOf(
            sortedMapOf("name" to "codex-mobile:archive-sha256", "value" to archiveHash),
            sortedMapOf("name" to "codex-mobile:source", "value" to "github.com/openai/codex release"),
        ),
        "purl" to codexRef,
        "type" to "application",
        "version" to codexVersion,
    )
    val maven = dependencies.map { (group, name, dependencyVersion) ->
        val ref = "pkg:maven/$group/$name@$dependencyVersion"
        sortedMapOf<String, Any>(
            "bom-ref" to ref, "group" to group, "name" to name,
            "purl" to ref, "type" to "library", "version" to dependencyVersion,
        )
    }
    val direct = internal + codex + maven
    val refs = direct.map { it.getValue("bom-ref") as String }.sorted()
    val metadata = sortedMapOf<String, Any>(
        "component" to sortedMapOf(
            "bom-ref" to appRef,
            "description" to "Independent Android Codex client with a portable shared runtime and UI.",
            "licenses" to licenses("GPL-3.0-or-later"),
            "name" to "Codex Mobile",
            "purl" to appRef,
            "type" to "application",
            "version" to version,
        ),
    )
    val bom = sortedMapOf<String, Any>(
        "bomFormat" to "CycloneDX",
        "components" to direct,
        "dependencies" to listOf(sortedMapOf("dependsOn" to refs, "ref" to appRef)) +
            refs.map { sortedMapOf("dependsOn" to emptyList<String>(), "ref" to it) },
        "metadata" to metadata,
        "serialNumber" to "urn:uuid:${uuid5(appRef)}",
        "specVersion" to "1.6",
        "version" to 1,
    )
    return prettyJson(bom) + "\n"
}

private fun component(name: String, version: String, description: String) = sortedMapOf<String, Any>(
    "bom-ref" to "pkg:generic/$name@$version",
    "description" to description,
    "licenses" to licenses("GPL-3.0-or-later"),
    "name" to name,
    "purl" to "pkg:generic/$name@$version",
    "type" to "library",
    "version" to version,
)

private fun licenses(id: String) = listOf(sortedMapOf("license" to sortedMapOf("id" to id)))

private fun uuid5(value: String): UUID {
    val namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    val bytes = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
    val hash = MessageDigest.getInstance("SHA-1").digest(bytes + value.toByteArray(StandardCharsets.UTF_8))
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    return ByteBuffer.wrap(hash).let { UUID(it.long, it.long) }
}

private fun prettyJson(value: Any?, depth: Int = 0): String = when (value) {
    null -> "null"
    is String -> "\"" + value.flatMap { character ->
        when (character) {
            '\\' -> "\\\\".toList()
            '"' -> "\\\"".toList()
            '\n' -> "\\n".toList()
            '\r' -> "\\r".toList()
            '\t' -> "\\t".toList()
            else -> listOf(character)
        }
    }.joinToString("") + "\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
        prefix = "{\n", postfix = "\n${"  ".repeat(depth)}}", separator = ",\n",
    ) { (key, item) -> "  ".repeat(depth + 1) + prettyJson(key.toString()) + ": " + prettyJson(item, depth + 1) }
    is Iterable<*> -> if (!value.iterator().hasNext()) "[]" else value.joinToString(
        prefix = "[\n", postfix = "\n${"  ".repeat(depth)}]", separator = ",\n",
    ) { item -> "  ".repeat(depth + 1) + prettyJson(item, depth + 1) }
    else -> error("unsupported JSON value: ${value::class}")
}

private fun java.io.File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val HASH = Regex("[0-9a-f]{64}")
