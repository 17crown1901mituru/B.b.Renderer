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
        // CI(GitHub Actions)からビルドごとに一意な値を渡す(2026-08対応)。
        // 背景: versionCodeが常に固定の1のままだったため、MT Managerの
        // 「インストール前にversionCodeを検証する」設定に引っかかり、APKの中身が
        // 変わっていても実質再インストールされない(古いビルドのまま)事故が起きていた。
        // BUILD_VERSION_CODEにはUnixエポック秒(ビルド時刻基準で単調増加・Intに収まる)を
        // 渡す想定。YYYYMMDDHHmm形式だと12桁になりInt(上限約21億)を超えるため使えない。
        // ローカルビルド等、環境変数が無い場合は固定値にフォールバックする。
        versionCode = System.getenv("BUILD_VERSION_CODE")?.toIntOrNull() ?: 1
        // versionNameの方はInt制約が無いので、APKファイル名と同じBUILD_DATE文字列を
        // そのまま付与できる。設定画面でインストール済みバージョンを目視確認しやすくなる。
        versionName = System.getenv("BUILD_DATE_LABEL")?.let { "0.1.0+$it" } ?: "0.1.0"
    }

    signingConfigs {
        create("githubActionsSign") {
            val envFile = System.getenv("KEYSTORE_FILE")
            if (!envFile.isNullOrEmpty() && file(envFile).exists()) {
                storeFile = file(envFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // GitHub Actions上でのビルド時のみ、固定の署名を適用する
            if (!System.getenv("KEYSTORE_FILE").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("githubActionsSign")
            }
        }
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
