plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

group = "com.quickvoice"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.quickvoice.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "QuickVoiceDialer"
            packageVersion = "1.0.0"
            description = "QuickVoice WiFi calling dialer for Windows"
            vendor = "QuickVoice"
        }
    }
}
