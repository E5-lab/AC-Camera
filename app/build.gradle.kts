import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing is configured from local.properties (local builds) or CI environment variables
// (signed-release workflow). When neither is set, the "release" build type simply stays unsigned.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val keystorePath: String? =
    (localProperties["AC_CAMERA_KEYSTORE_PATH"] as? String ?: System.getenv("AC_CAMERA_KEYSTORE_PATH"))
        ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.e5lab.accamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.e5lab.accamera"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Populated only when a keystore is configured (local.properties for local builds,
            // CI environment variables for the signed-release workflow). The store file is left
            // null when absent so an unsigned build still works for debug/dev.
            storeFile = keystorePath?.let { file(it) }
            storePassword =
                (localProperties["AC_CAMERA_KEYSTORE_PASSWORD"] as? String) ?: System.getenv("AC_CAMERA_KEYSTORE_PASSWORD")
            keyAlias =
                (localProperties["AC_CAMERA_KEY_ALIAS"] as? String) ?: System.getenv("AC_CAMERA_KEY_ALIAS")
            keyPassword =
                (localProperties["AC_CAMERA_KEY_PASSWORD"] as? String) ?: System.getenv("AC_CAMERA_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign only when a keystore is actually available.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared Kotlin Multiplatform core (domain algorithms + platform-agnostic models)
    implementation(project(":shared"))

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room (local persistence, offline-first)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
}
