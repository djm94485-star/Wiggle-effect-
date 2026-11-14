plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.wiggle.wall"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wiggle.wall"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
