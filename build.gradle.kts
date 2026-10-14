// Top-level build file. Module configuration lives in app/build.gradle.kts.
buildscript {
    dependencies {
        // AGP 9 ships with built-in Kotlin support and a runtime dependency on
        // Kotlin Gradle Plugin 2.2.10. Declaring a newer KGP here upgrades the
        // Kotlin compiler used by the whole build (documented AGP 9 approach).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
