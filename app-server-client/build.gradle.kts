import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    kotlin("multiplatform") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = "0.145.0-1"

dependencyLocking { lockAllConfigurations() }

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

val protocolSchema = layout.projectDirectory.file(
    "protocol/codex_app_server_protocol.v2.schemas.json",
)
val completeProtocolSchema = layout.projectDirectory.file(
    "protocol/codex_app_server_protocol.schemas.json",
)
val protocolProvenance = layout.projectDirectory.file("protocol/provenance.json")

val verifyProtocolSource = tasks.register("verifyProtocolSource") {
    notCompatibleWithConfigurationCache("Reads and hashes pinned protocol provenance directly")
    inputs.files(protocolSchema, completeProtocolSchema, protocolProvenance)
    inputs.property("expectedSha256", "32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4")
    doLast {
        fun File.sha256(): String = inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val actual = protocolSchema.asFile.sha256()
        check(actual == inputs.properties.getValue("expectedSha256")) {
            "Pinned App Server protocol schema digest changed: $actual"
        }
        check(completeProtocolSchema.asFile.sha256() == "8039a1222460b3846a3688c61eb4b2626b451d61b9c2b36b83fea0ce341ce0be") {
            "Pinned complete App Server protocol schema digest changed"
        }
        @Suppress("UNCHECKED_CAST")
        val provenance = JsonSlurper().parse(protocolProvenance.asFile) as Map<String, Any?>
        val generator = provenance["generator"] as? Map<String, Any?>
            ?: error("Protocol generator provenance is missing")
        check(generator["version"] == "2") { "Unsupported protocol generator provenance" }
        val outputs = generator["outputs"] as? List<Map<String, String>>
            ?: error("Generated protocol outputs are missing from provenance")
        check(outputs.isNotEmpty()) { "Generated protocol output provenance is empty" }
        outputs.forEach { output ->
            val file = layout.projectDirectory.file(output.getValue("path")).asFile
            check(file.isFile) { "Generated protocol output is missing: ${output.getValue("path")}" }
            val outputDigest = file.sha256()
            check(outputDigest == output.getValue("sha256")) {
                "Generated protocol output drifted: ${output.getValue("path")}"
            }
        }
    }
}

tasks.register("updateProtocol") {
    group = "protocol"
    description = "Regenerates pinned protocol descriptors from an exact Codex common.rs source"
    dependsOn(":protocol-generator:generateProtocol")
}

tasks.named("check").configure { dependsOn(verifyProtocolSource) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
