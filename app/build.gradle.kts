plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sene.admin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sene.admin"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
}

// La APK y su etiqueta de instalación se identifican como SeneAdmin.
