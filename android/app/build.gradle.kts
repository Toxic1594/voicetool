plugins {
    id("com.android.application")
}

android {
    namespace = "de.voicetool.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.voicetool.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

