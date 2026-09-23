import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.jmdns)
}

application {
    mainClass = "dev.dsmirnov.photoframe.framectl.MainKt"
}

// Lets `gradlew :framectl:run --args="..."` read a friend code typed at the prompt.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
