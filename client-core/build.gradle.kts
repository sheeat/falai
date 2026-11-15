plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Núcleo do app da família: pareamento, conexão, fila de envio e banco local.
// Kotlin puro, para ser testado no PC contra o servidor de verdade.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":shared"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation(project(":server-core"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
