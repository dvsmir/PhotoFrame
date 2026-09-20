pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "FrameAlt"

// The protocol module is plain Kotlin/JVM on purpose: it must stay testable on a
// laptop JVM with no emulator and no frame. See Spec/01 - Specification/02 - Architecture.md §1.
include(":protocol")

// :app is added in Phase 2, when the Android SDK platform is actually needed.
