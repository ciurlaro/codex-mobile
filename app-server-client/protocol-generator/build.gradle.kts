plugins {
    kotlin("jvm")
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

val generateProtocol = tasks.register<JavaExec>("generateProtocol") {
    group = "protocol"
    description = "Extracts exact stable-v2 route descriptors from the pinned upstream sources"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.ciurlaro.codexmobile.appserver.generator.ProtocolGeneratorKt"
    val commonSource = providers.gradleProperty("codexProtocolCommon")
    val schemaSource = providers.gradleProperty("codexProtocolSchema")
    val threadSource = providers.gradleProperty("codexProtocolThread")
    val turnSource = providers.gradleProperty("codexProtocolTurn")
    doFirst {
        check(commonSource.isPresent && schemaSource.isPresent && threadSource.isPresent && turnSource.isPresent) {
            "Pass -PcodexProtocolSchema=/path/to/codex_app_server_protocol.schemas.json and " +
                "-PcodexProtocolCommon=/path/to/common.rs, -PcodexProtocolThread=/path/to/thread.rs, " +
                "and -PcodexProtocolTurn=/path/to/turn.rs"
        }
        val common = file(commonSource.get())
        val schema = file(schemaSource.get())
        val thread = file(threadSource.get())
        val turn = file(turnSource.get())
        check(common.isFile) { "Pinned common.rs source is unavailable: $common" }
        check(schema.isFile) { "Pinned complete schema source is unavailable: $schema" }
        check(thread.isFile) { "Pinned thread.rs source is unavailable: $thread" }
        check(turn.isFile) { "Pinned turn.rs source is unavailable: $turn" }
        args(
            schema,
            common,
            thread,
            turn,
            rootProject.file("protocol/codex_app_server_protocol.schemas.json"),
            rootProject.file("protocol/descriptors.json"),
            rootProject.file(
                "src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated/GeneratedProtocolDescriptors.kt",
            ),
            rootProject.file(
                "src/commonMain/kotlin/io/github/ciurlaro/codexmobile/appserver/protocol/generated/GeneratedProtocolModels.kt",
            ),
            rootProject.file("protocol/provenance.json"),
        )
    }
    outputs.upToDateWhen { false }
}
