plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Para el subproyecto :m800-kmp-sdk (reemplazo 100% Kotlin de los jars del SDK M800).
    id("com.android.library") version "8.5.2" apply false
    kotlin("multiplatform") version "1.9.24" apply false
}
