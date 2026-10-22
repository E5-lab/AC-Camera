plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.7.3"
    kotlin("plugin.serialization") version "1.9.25"
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Material3 不在桌面聚合依赖中，需单独声明（安卓端通过 BOM 引入）。
                implementation(compose.material3)
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                // PDF 底图：把图纸 PDF 的第 1 页渲染成位图（安卓端用系统 PdfRenderer）。
                // 通过反射调用，缺失时只影响 PDF 导入、不会让整个应用启动失败。
                implementation("org.apache.pdfbox:pdfbox:2.0.31")
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
            // Installer outputs (built by CI on windows-latest):
            //   build/compose/binaries/main/msi/AC-Camera-1.0.0.msi   (needs WiX Toolset, installed via choco in CI)
            //   build/compose/binaries/main/exe/AC-Camera-1.0.0.exe   (needs Inno Setup, installed via choco in CI)
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            description = "AC-Camera equal-SPL field measurement (Windows desktop)"
            copyright = "E5-lab"
            vendor = "E5-lab"
            // MSI/EXE also bundle the JRE, so the installer is fully self-contained.
            windows {
                // Explicit icon (classic BMP .ico): jpackage's MSI/EXE bundler is picky
                // about icon format and choked on the default icon with "Input length = 1".
                iconFile.set(project.file("icon.ico"))
                // Per-user install avoids UAC prompts and writes under %LOCALAPPDATA%.
                perUserInstall = true
                // Let the user pick the install directory from the installer wizard.
                dirChooser = true
                // Add a Start Menu shortcut group.
                menu = true
                // Create a desktop shortcut.
                shortcut = true
            }
        }
    }
}
