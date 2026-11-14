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
    "com/mobiledaw/cloud/CloudPlatform.kt",
    "com/mobiledaw/ui/ResizeCursor.kt",
    "com/mobiledaw/sound/MediaPrep.kt",
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

// Locally, use the machine's Node and Yarn (the sandbox can't download them); CI downloads its own.
val downloadTools = System.getenv("CI") == "true"
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = downloadTools
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().download = downloadTools
}

// The APK's assets (presets, and the GeneralUser GS SoundFont when present) under assets/, and the
// engine built by web/engine/build.sh, served next to the page.
val webExtras = layout.buildDirectory.dir("generated/webExtras")
val copyWebExtras = tasks.register<Copy>("copyWebExtras") {
    into(webExtras)
    from(rootDir.resolve("../../app/src/main/assets")) { into("assets") }
    from(rootDir.resolve("../public/engine.wasm"))
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(copyWebExtras) }
