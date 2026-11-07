plugins {
    // AGP 9 has built-in Kotlin support, so the standalone kotlin-android
    // plugin is no longer applied — only the Compose compiler plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
