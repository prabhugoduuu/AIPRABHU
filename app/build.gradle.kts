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
        versionCode = 102
        versionName = "2.6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
