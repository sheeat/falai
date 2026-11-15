pluginManagement {
    plugins {
        id("com.android.application") version "8.13.0"
        kotlin("android") version "2.4.21"
        kotlin("jvm") version "2.4.21"
        kotlin("plugin.serialization") version "2.4.21"
        kotlin("plugin.compose") version "2.4.21"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "falai"

// Núcleo do servidor: Kotlin puro, roda e é testado em qualquer JVM.
include(":shared")
include(":server-core")
include(":client-core")

// Casca Android do servidor. Só entra quando há um Android SDK disponível
// (o Android Studio cria o local.properties com sdk.dir automaticamente).
val hasAndroidSdk = file("local.properties").exists() ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk) {
    include(":server-android")
    include(":app")
}
