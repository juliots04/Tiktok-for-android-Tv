plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.tiktokxsleppify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tiktokxsleppify"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.15"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.browser)
}