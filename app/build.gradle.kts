import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.iaguito22.oto"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iaguito22.oto"
        // 33: RuntimeShader (AGSL) para la gota y RenderEffect para el desenfoque, sin caminos alternativos.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // La clave del PoToken va en local.properties (o en la variable de entorno), nunca en el repo.
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load) }
        val googleApiKey = local.getProperty("GOOGLE_API_KEY") ?: System.getenv("GOOGLE_API_KEY").orEmpty()
        buildConfigField("String", "GOOGLE_API_KEY", "\"$googleApiKey\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // La release tal cual (R8 incluido) firmada con la clave de debug: para medir rendimiento en el móvil.
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":innertube"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.palette)
    implementation(libs.datastore)
    implementation(libs.timber)

    coreLibraryDesugaring(libs.desugaring)
}
