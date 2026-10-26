plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.comiclsposed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.comiclsposed"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
