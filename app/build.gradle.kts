plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.prabhu.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prabhu.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 101
        versionName = "2.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
