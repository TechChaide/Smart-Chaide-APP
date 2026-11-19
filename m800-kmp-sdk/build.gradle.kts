plugins {
    // Versiones declaradas en el build.gradle.kts raiz (Kotlin 2.1.0, AGP 8.5.2).
    kotlin("multiplatform")
    id("com.android.library")
}

group = "com.chaide.m800sdk"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.chaide.m800sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
