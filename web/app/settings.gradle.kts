// The web build of the app: the Android app's own screens and logic (app/src/main/java),
// compiled with Compose Multiplatform for the browser (Kotlin/Wasm). Separate from the Android
// build so it needs no Android SDK. Run from the repo root: ./gradlew -p web/app wasmJsBrowserDistribution
pluginManagement { repositories { mavenCentral(); gradlePluginPortal(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "mobile-daw-web"
