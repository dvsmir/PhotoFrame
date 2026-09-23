pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "PhotoFrame"

// Plain Kotlin/JVM on purpose: the protocol must stay testable on a laptop JVM with no
// emulator and no frame. See Spec/00 - Initial/02 - Architecture.md §1.
include(":protocol")

// Desktop CLI over the same library — validates the protocol against real hardware
// without involving Android at all.
include(":framectl")

include(":app")
