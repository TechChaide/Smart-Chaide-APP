plugins {
    // Versions match the parent app's root build.gradle.kts when this module is
    // included as its :m800-kmp-sdk subproject — kept explicit here too so this
    // module stays independently buildable/testable on its own (as validated
    // standalone with `compileDebugKotlinAndroid`, see README).
    kotlin("multiplatform") version "1.9.24"
    id("com.android.library") version "8.5.2"
}

group = "com.chaide.m800sdk"
version = "0.1.0"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidMain by getting
        val androidUnitTest by getting

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
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
