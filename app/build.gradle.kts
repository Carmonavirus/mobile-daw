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

    buildFeatures {
        compose = true
        prefab = true
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
}
