import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":innertube"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    implementation(libs.org.json)
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:jorbis:0.0.17.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "io.github.iaguito22.oto.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.AppImage,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "Oto"
            packageVersion = "1.2.2"
            description = "Oto — Tu música de YouTube Music con estética Caelestia"
            vendor = "iaguito22"
            modules("java.instrument", "java.management", "java.naming", "java.prefs", "java.sql", "jdk.unsupported", "jdk.httpserver", "java.desktop", "java.net.http")
            linux {
                packageName = "oto"
                debMaintainer = "hola@ejemplo.com"
                menuGroup = "AudioVideo;Audio;Player;"
                appCategory = "Audio"
            }
            windows {
                menuGroup = "Oto"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8f45e92a-3c74-4b2e-9a81-56e8049a1201"
            }
        }
    }
}
