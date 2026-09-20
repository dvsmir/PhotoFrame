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

// Plain Kotlin/JVM on purpose: the protocol must stay testable on a laptop JVM with no
// emulator and no frame. See Spec/00 - Initial/02 - Architecture.md §1.
include(":protocol")
