import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    kotlin("multiplatform") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile"
version = "0.144.6-1"

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
    inputs.property("expectedSha256", "007e12d25541eb0a50bc778dfcff9e6ab88b3124c9425c4e8f79391d3538bec0")
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
        check(completeProtocolSchema.asFile.sha256() == "5c40798d0ea83e14988a6f73e854f905f35df8b8c41c4ac61afb67f8698a4c4f") {
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
