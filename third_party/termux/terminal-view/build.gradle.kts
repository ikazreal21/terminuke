plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    api(project(":third_party:termux:terminal-emulator"))
}
