// Copiado de Metrolist (github.com/MetrolistGroup/Metrolist, commit b1dd1d0, GPL-3.0).
// El paquete se queda en com.metrolist.innertube para poder traer sus arreglos sin reescribir imports.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.metrolist.innertube"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.innertubex)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)

    coreLibraryDesugaring(libs.desugaring)
}
