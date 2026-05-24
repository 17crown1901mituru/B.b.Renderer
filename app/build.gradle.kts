plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bb.renderer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bb.renderer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ネイティブビルドの設定
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // CMakeLists.txt の場所を指定
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }
}
