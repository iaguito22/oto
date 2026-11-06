import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

val windowsHost = System.getProperty("os.name").startsWith("Windows", true)
val audioToolsDir = providers.environmentVariable("OTO_FFMPEG_DIR").orElse(providers.provider {
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .firstOrNull { File(it.trim('"'), "ffplay.exe").isFile }.orEmpty()
})
val prepareWindowsAudio = tasks.register<Sync>("prepareWindowsAudio") {
    val enabledOnHost = windowsHost
    val toolsDirectory = audioToolsDir
    onlyIf { enabledOnHost }
    from(toolsDirectory) { include("ffmpeg.exe", "ffplay.exe", "ffprobe.exe"); into("windows/audio") }
    from(toolsDirectory.map { File(it).parentFile }) { include("LICENSE", "README.txt"); into("windows/audio") }
    into(layout.buildDirectory.dir("nativeResources"))
    doFirst {
        for (name in listOf("ffmpeg.exe", "ffplay.exe", "ffprobe.exe")) {
            require(File(toolsDirectory.get()).resolve(name).isFile) {
                "Para empaquetar Oto en Windows, configura OTO_FFMPEG_DIR con la carpeta bin de FFmpeg (falta $name)."
            }
        }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareWindowsAudio) }

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
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.google.zxing:core:3.5.3")
    // Ya llega con el escritorio: acceso explícito a RegisterHotKey en Windows.
    implementation("net.java.dev.jna:jna-platform:5.6.0")
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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "io.github.iaguito22.oto.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("nativeResources"))
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.AppImage,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "Oto"
            packageVersion = "2.1.0"
            description = "Oto — Tu música, a tu manera"
            vendor = "iaguito22"
            modules("java.instrument", "java.management", "jdk.management","java.naming", "java.prefs", "java.sql", "jdk.unsupported", "jdk.httpserver", "java.desktop", "java.net.http")
            linux {
                packageName = "oto"
                debMaintainer = "hola@ejemplo.com"
                menuGroup = "AudioVideo;Audio;Player;"
                appCategory = "Audio"
            }
            windows {
                iconFile.set(project.file("src/main/resources/oto-icon.ico"))
                installationPath = "Oto Desktop"
                menuGroup = "Oto"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8f45e92a-3c74-4b2e-9a81-56e8049a1201"
            }
        }
    }
}
