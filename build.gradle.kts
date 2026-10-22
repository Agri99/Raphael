// Top-level build script for Raphael Android project
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.23" apply false
}

subprojects {
    repositories {
        google()
        mavenCentral()
    }
}
