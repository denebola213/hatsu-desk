plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core は JVM だけで動く。android プラグインも Android SDK も要らない（ADR 0007）。
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // 「:core に android.* が無い」検査がリポジトリのどこから走っても src を見つけられるようにする。
    systemProperty("hatsudesk.core.src", layout.projectDirectory.dir("src").asFile.absolutePath)
}
