plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.agri.karen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agri.karen"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KAREN_KEYSTORE_PATH")
                ?: System.getenv("RAPHAEL_KEYSTORE_PATH")
                ?: (project.findProperty("KAREN_KEYSTORE_PATH") as? String)
                ?: (project.findProperty("RAPHAEL_KEYSTORE_PATH") as? String)
                ?: rootProject.file("keystore/karen_release.jks").takeIf { it.exists() }?.absolutePath
                ?: rootProject.file("keystore/release.jks").takeIf { it.exists() }?.absolutePath
            val storePass = System.getenv("KAREN_KEYSTORE_PASSWORD")
                ?: System.getenv("RAPHAEL_KEYSTORE_PASSWORD")
                ?: (project.findProperty("KAREN_KEYSTORE_PASSWORD") as? String)
                ?: (project.findProperty("RAPHAEL_KEYSTORE_PASSWORD") as? String)
            val keyAliasName = System.getenv("KAREN_KEY_ALIAS")
                ?: System.getenv("RAPHAEL_KEY_ALIAS")
                ?: (project.findProperty("KAREN_KEY_ALIAS") as? String)
                ?: (project.findProperty("RAPHAEL_KEY_ALIAS") as? String)
            val keyPass = System.getenv("KAREN_KEY_PASSWORD")
                ?: System.getenv("RAPHAEL_KEY_PASSWORD")
                ?: (project.findProperty("KAREN_KEY_PASSWORD") as? String)
                ?: (project.findProperty("RAPHAEL_KEY_PASSWORD") as? String)

            if (!keystorePath.isNullOrBlank() && !storePass.isNullOrBlank() && !keyAliasName.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                val f = file(keystorePath)
                if (f.exists()) {
                    storeFile = f
                    storePassword = storePass
                    keyAlias = keyAliasName
                    keyPassword = keyPass
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    // Room persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // DataStore and Security
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Networking & Serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.citest)
}
