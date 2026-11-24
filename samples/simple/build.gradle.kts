// Aplikasi contoh milik proyek untuk menguji ClonApps (Sample Simple). Tanpa dependensi pihak ketiga.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.clonsapps.sample.simple"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.clonsapps.sample.simple"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
