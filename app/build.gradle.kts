plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.st.hatsudesk"
    compileSdk = 37
    // androidx.compose.ui 1.13.0 が API 37.1 以上を要求するため 37.2 を指す
    compileSdkMinor = 2

    // AGP 9.5 の既定 build-tools は 36.0.0。compileSdk 37 に合わせて 37.0.0 を固定する。
    // イメージ側（build/Containerfile）も 36.0.0 と 37.0.0 の両方を焼いているので、
    // ビルド時に SDK を取りに行かない。
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.st.hatsudesk"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 画面に出す版情報はここから作る。手書きの文字列が設定からずれないように。
        buildConfigField("String", "COMPILE_SDK", "\"$compileSdk.${compileSdkMinor ?: 0}\"")
        buildConfigField("String", "BUILD_TOOLS_PIN", "\"$buildToolsVersion\"")
        buildConfigField("String", "TARGET_SDK", "\"$targetSdk\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}
