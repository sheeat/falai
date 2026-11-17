plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val ktorVersion = "3.6.1"

// Bytecode Java 17: compatível com Android (API 28+) e com qualquer JDK 17 ou mais novo.
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
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")
    api("io.ktor:ktor-server-websockets:$ktorVersion")
    // Vídeo no navegador: deixa pular para o meio (pedidos "Range").
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // SQLite via JDBC: usado para rodar o servidor no PC e nos testes.
    // No Android, o banco nativo do sistema é usado (ver server-android).
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    // Gera o QR Code da página "Falaí no computador".
    implementation("com.google.zxing:core:3.5.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Só nos testes: assina e confere um APK de mentira (o carimbo do convite não pode quebrar a assinatura).
    testImplementation("com.android.tools.build:apksig:2.3.0")
}

application {
    mainClass.set("app.falai.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    // A biblioteca de assinatura de APK (só nos testes) usa uma parte interna do Java.
    jvmArgs("--add-exports", "java.base/sun.security.x509=ALL-UNNAMED", "--add-exports", "java.base/sun.security.pkcs=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
