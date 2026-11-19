plugins {
    // App multiplataforma (Android + iOS) con Compose Multiplatform — ver app/build.gradle.kts.
    id("com.android.application") version "8.5.2" apply false
    // Para el subproyecto :m800-kmp-sdk (reemplazo 100% Kotlin de los jars del SDK M800).
    id("com.android.library") version "8.5.2" apply false
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}
