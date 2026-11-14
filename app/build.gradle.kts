import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mobiledaw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobiledaw"
        minSdk = 26
        targetSdk = 35
        // CI stamps each build with its run number, so every published APK is a newer version.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.3.$build"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        // A fixed debug key checked into the repo, so every CI build is signed the same way and
        // Android installs new builds over the old one instead of refusing the update.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI artifact can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // The SoundFont is read straight from the APK (memory-mapped), so keep it uncompressed.
        noCompress += "sf2"
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.oboe)
    implementation(libs.androidx.documentfile)
    // Google sign-in for Drive sync (authorization only: drive.file scope).
    implementation(libs.play.services.auth)
}

// GeneralUser GS, a free General MIDI SoundFont by S. Christian Collins (see THIRD_PARTY_NOTICES.md),
// ships inside the APK as an asset. It is fetched at build time from its official GitHub repository
// (pinned commit + checksum) instead of being committed, to keep 32 MB out of git history.
val generalUserUrl =
    "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/684543d5e5efaef08d02be50dcda8d552478fa60/GeneralUser-GS.sf2"
val generalUserSha256 = "9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe"
val generalUserFile = file("src/main/assets/soundfonts/GeneralUser-GS.sf2")

val fetchGeneralUser = tasks.register("fetchGeneralUserGs") {
    outputs.file(generalUserFile)
    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }
        if (generalUserFile.exists() && sha256(generalUserFile) == generalUserSha256) return@doLast
        generalUserFile.parentFile.mkdirs()
        val tmp = File(generalUserFile.path + ".part")
        URI(generalUserUrl).toURL().openStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
        check(sha256(tmp) == generalUserSha256) { "GeneralUser GS download did not match its checksum" }
        tmp.renameTo(generalUserFile)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchGeneralUser) }
