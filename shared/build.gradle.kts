plugins {
    id("com.android.library")
    kotlin("multiplatform")
}

android {
    namespace = "com.e5lab.accamera.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
}

kotlin {
    android()
    jvm()

    sourceSets {
        val commonMain by getting
    }
}
