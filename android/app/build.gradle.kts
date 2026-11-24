plugins { id("com.android.application") }

android {
    namespace = "dev.penrelay.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.penrelay.app"
        if (providers.gradleProperty("p2Lab").isPresent) applicationIdSuffix = ".p2lab"
        minSdk = 33
        targetSdk = 37
        versionCode = 42
        versionName = "0.11.12"
        testInstrumentationRunner = "dev.penrelay.app.WifiCheck"
    }
}

dependencies {
    implementation(project(":session"))
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.15.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
