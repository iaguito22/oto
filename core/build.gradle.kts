plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":innertube"))
    api(libs.coroutines.core)
    api(libs.datastore.core)
    api(libs.org.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    testImplementation(libs.junit)
}
