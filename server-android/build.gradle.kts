plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.falai.server.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.falai.server"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Arquivos repetidos que vêm junto das bibliotecas do Ktor/coroutines.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":server-core")) {
        // No Android usamos o SQLite do próprio sistema (AndroidDb), não o JDBC.
        exclude(group = "org.xerial", module = "sqlite-jdbc")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
