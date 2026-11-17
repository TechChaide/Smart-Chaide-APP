pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ChaideApp"
include(":app")

// SDK M800 propio (reemplaza m800sdk.jar/sdkcore.jar/wificonfigsdk.jar/
// wifidevicecommonsdk.jar) — 100% Kotlin, ver m800-kmp-sdk/README.md.
// Incluido como subproyecto real (no composite build) para que el target
// Android de este módulo KMP resuelva sin fricciones de variantes.
// Vive DENTRO de este repo (antes era un directorio hermano fuera del repo,
// lo que rompía un `git clone` limpio en otra máquina — p.ej. para probar
// los targets iOS en una Mac).
include(":m800-kmp-sdk")
