plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.B.b.Renderer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.B.b.Renderer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    // --- コアUI ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0") // デバッグドロワー用

    // --- HTMLパース(DOM構築用) ---
    implementation("org.jsoup:jsoup:1.17.2")

    // --- HTTP通信(fetch/XHR相当、HTMX連携) ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- JSエンジン ---
    implementation("org.mozilla:rhino:1.9.1") // content用・device用ともにこの1本で完結する(bsh廃止)

    // --- アプリ内ショートカット/マクロ用スクリプトエンジン ---

    // --- メディア再生(video/audio, mediaPlayback Foreground Service) ---
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    // --- コルーチン(非同期処理) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- テスト ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
