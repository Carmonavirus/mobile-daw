import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// Android app files compiled for the web as they are. The rest of the app's files talk to Android
// itself (JNI engine, files, microphone...); each has a web version with the same API in src/wasmJsMain.
val appSrc = rootDir.resolve("../../app/src/main/java")
val androidOnly = listOf(
    "com/mobiledaw/MainActivity.kt",
    "com/mobiledaw/PhoneTurn.kt",
    "com/mobiledaw/audio/NativeEngine.kt",
    "com/mobiledaw/audio/SynthEngine.kt",
    "com/mobiledaw/audio/Recorder.kt",
    "com/mobiledaw/export/ExportStorage.kt",
)

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "daw"
        browser { commonWebpackConfig { outputFileName = "daw.js" } }
        binaries.executable()
    }
    sourceSets {
        val wasmJsMain by getting {
            kotlin.srcDir(appSrc)
            kotlin.exclude(androidOnly)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.4")
            }
        }
    }
}

// Use the machine's Node and Yarn instead of downloading them.
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().download = false
}
