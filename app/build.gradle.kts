plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.magazinryadom"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.magazinryadom"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.9.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
}
