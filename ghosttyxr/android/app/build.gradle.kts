import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin via built-in support; no kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


// Play Store upload keystore (see local.properties wiring above). The
// keystore file itself lives outside the repo (~/.gplay/); only its path and
// passwords are read from the gitignored local.properties. Falls back to the
// debug keystore when release.* isn't set, so `assembleRelease` still works
// for local sideload testing without the Play upload key.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String = localProperties.getProperty("release.storeFile", "")

android {
    namespace = "com.ghosttyxr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ghosttyxr.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 16
        versionName = "0.1.15"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        if (releaseStoreFile.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProperties.getProperty("release.storePassword", "")
                keyAlias = localProperties.getProperty("release.keyAlias", "")
                keyPassword = localProperties.getProperty("release.keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 full mode (AGP 9 default): shrink + obfuscate + optimize.
            // Rules: proguard-android-optimize.txt + app/proguard-rules.pro
            // (JNI GhosttyBridge/NativeSshBridge).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the Play upload keystore when release.* is present in
            // local.properties; otherwise fall back to the debug keystore so
            // `assembleRelease` still produces an installable (sideload-only)
            // APK for contributors without the Play upload key.
            signingConfig = if (releaseStoreFile.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Needed for the BuildConfig.DEBUG gate on the emulator debug-pairing
        // path (see DebugPairing / MainActivity). AGP 9 no longer generates
        // BuildConfig unless explicitly opted in.
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    testOptions {
        // KeyMapperTest exercises android.view.KeyEvent constants only —
        // no behaviour methods — so the stock mockable-android.jar is
        // enough; Robolectric isn't needed.
        unitTests.isReturnDefaultValues = true
    }

    // Shared window-mirror compatibility contract + frozen vectors live outside
    // the Android tree so Kotlin and Swift tests consume the same fixtures.
    // Path: ghosttyxr/protocol/window-mirror/ → app module is ghosttyxr/android/app.
    sourceSets {
        getByName("test") {
            resources.directories.add("../../protocol/window-mirror")
        }
    }

    lint {
        // The `Instantiatable` check misfires on `MainActivity` because the
        // androidx XR Compose classpath isn't resolved during lint analysis,
        // so it can't see that `ComponentActivity` (which MainActivity
        // extends) inherits from `Activity`. Lint errors shouldn't block
        // release APK production — gradle build is the source of truth for
        // compilation correctness.
        abortOnError = false
    }
}

// AGP 9 built-in Kotlin: the kotlinOptions{} DSL is gone, so the (AGP-built-in)
// Kotlin compiler target is set via the top-level kotlin{} extension. Emits JVM 17
// bytecode while running Gradle on JDK 21 (JDK 25 crashes the Kotlin compiler),
// so no separate JDK-17 toolchain provisioning is required.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    // ARCore for Jetpack XR — hand tracking for the palm-up hand menu.
    implementation(libs.androidx.xr.arcore)
    // Compile-only XR platform extensions stub (matches Google's xr-samples).
    compileOnly(libs.androidx.xr.extensions)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    // Encrypted-at-rest storage (Android Keystore-backed) for the persistent
    // SSH private key the headset uses to auto-reconnect to a paired Mac, and
    // the Deepgram API key for voice input.
    implementation(libs.androidx.security.crypto)
    // HTTP client for the Deepgram batch speech-to-text POST (voice input).
    // WebSocket support in the same dep is reusable for the future streaming
    // phase, so we won't re-tool later.
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    // JSON value types for the Gemini Live function-call args/results.
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Local unit tests (`./gradlew :app:testDebugUnitTest`).
    testImplementation(libs.junit)
    // DeepgramClient response parsing uses android's bundled org.json. The
    // mockable android.jar returns default values for it, so pull in the real
    // impl for host-JVM tests that parse fixture responses.
    testImplementation(libs.json)
    // VoiceCommandController args/results are kotlinx-serialization JSON values.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests (`./gradlew :app:connectedDebugAndroidTest`). Must
    // run on the official Android XR AVD per Google's testing path —
    // see TESTING.md for `system-images/android-34/google-xr/arm64-v8a`
    // setup and the GhosttyXR_Headset AVD config.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // Compose UI tests for SpatialPanel / TerminalCanvas semantics.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // `ui-test-manifest` adds ComponentActivity to the debug manifest so
    // `createAndroidComposeRule<ComponentActivity>()` can launch it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
