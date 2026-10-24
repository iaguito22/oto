// Copiado de Metrolist (github.com/MetrolistGroup/Metrolist, commit b1dd1d0, GPL-3.0).
// El paquete se queda en com.metrolist.innertube para poder traer sus arreglos sin reescribir imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
