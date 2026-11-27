plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // :core は JVM モジュール。バージョンをルートで確定させないと、AGP が持ち込む
    // Kotlin と版が突き合わされず「unknown version」で解決に失敗する。
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
