plugins {
    id("org.jetbrains.kotlin.jvm")
}

layout.buildDirectory.set(
    rootProject.layout.projectDirectory.dir(
        "build/modules/${project.path.removePrefix(":").replace(':', '/')}",
    ),
)

kotlin {
    jvmToolchain(17)
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
