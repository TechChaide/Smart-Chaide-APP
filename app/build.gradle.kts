import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

/*
 * Chaide Smart — app multiplataforma (Android + iOS) con Compose Multiplatform.
 *
 *  - commonMain : pantallas Compose, navegacion, red (Ktor), sesion, protocolo
 *                 M800 y flujo de aprovisionamiento BLE — compartido al 100%.
 *  - androidMain: MainActivity, permisos Android, MediaPlayer, motor OkHttp.
 *  - iosMain    : MainViewController (lo consume iosApp/ en Xcode), AVPlayer,
 *                 motor Darwin (NSURLSession).
 */
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ChaideApp"
            isStatic = true
            // Exporta el SDK para que Swift pueda verlo si hiciera falta.
            export(project(":m800-kmp-sdk"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":m800-kmp-sdk"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
            implementation("io.ktor:ktor-client-logging:3.0.3")

            implementation("com.russhwolf:multiplatform-settings-no-arg:1.2.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.chaide.app.resources"
    generateResClass = always
}

android {
    namespace = "com.chaide.app"
    compileSdk = 34

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        assets.srcDirs("src/androidMain/assets")
    }

    defaultConfig {
        applicationId = "com.chaide.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
