plugins {
    // AGP 9 has built-in Kotlin support, so the standalone kotlin-android
    // plugin is no longer applied — only the Compose compiler plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Firebase config plugin; applied by :app only when google-services.json
    // exists (see app/build.gradle.kts), so the repo builds without it.
    alias(libs.plugins.google.services) apply false
}
