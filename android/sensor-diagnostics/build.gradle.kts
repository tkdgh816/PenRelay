plugins {
    id("com.android.application")
}

android {
    namespace = "dev.penrelay.diagnostics"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.penrelay.diagnostics"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}
