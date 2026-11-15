plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Código comum ao servidor e ao app: protocolo (JSON) e acesso ao banco.
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
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
}
