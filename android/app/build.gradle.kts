plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.blockhost.screenlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.blockhost.screenlink"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave debug para poder instalarlo directamente (sideload)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
