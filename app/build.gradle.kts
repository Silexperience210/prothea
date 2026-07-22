plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.silexperience.prothea"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.silexperience.prothea"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Le modele TFLite ne doit pas etre compresse dans l'APK (openFd)
    aaptOptions {
        noCompress("tflite")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val cameraxVersion = "1.3.4"

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ARCore (optionnel : l'app fonctionne sans, en mode degrade)
    implementation("com.google.ar:core:1.45.0")

    // Chiffrement local des metadonnees de session
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Estimation de profondeur monoculaire on-device (camera frontale)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // Photogrammetrie on-device (SIFT, matrice essentielle, triangulation)
    implementation("org.opencv:opencv:4.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
