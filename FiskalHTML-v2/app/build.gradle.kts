plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "rs.fiskalhtml"
    compileSdk = 35

    defaultConfig {
        applicationId = "rs.fiskalhtml"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
