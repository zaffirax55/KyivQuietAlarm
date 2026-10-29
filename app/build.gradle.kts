plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.kyivquiet.alarm"
    compileSdk = 35
    defaultConfig { applicationId = "com.kyivquiet.alarm"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "5.0" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
