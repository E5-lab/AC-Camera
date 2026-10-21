plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.5.15"
    kotlin("plugin.serialization") version "1.9.25"
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.e5lab.accamera.desktop.MainKt"
        nativeDistributions {
            packageName = "AC-Camera"
            packageVersion = "1.0.0"
            // No installer target formats -> CI uses createDistributable (portable, bundled JRE).
            // Producing an .msi/.exe installer would require the WiX toolset on the runner.
        }
    }
}
