pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform 编译器（org.jetbrains.compose.compiler:compiler）
        // 对 Kotlin 1.9.x 的版本仅发布在 JetBrains Compose 开发仓库，不在 Maven Central。
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "AC-Camera"
include(":app", ":shared", ":desktopApp")
