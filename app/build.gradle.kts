plugins {
    id("com.android.application")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "cl.ti3042.caracol"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cl.ti3042.caracol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
