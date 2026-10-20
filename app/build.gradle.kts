import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Claves que no van al repo: la del PoToken y la de firmar las releases.
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load) }

android {
    namespace = "io.github.iaguito22.oto"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.iaguito22.oto"
        // 33: RuntimeShader (AGSL) para la gota y RenderEffect para el desenfoque, sin caminos alternativos.
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // La clave del PoToken va en local.properties (o en la variable de entorno), nunca en el repo.
        val googleApiKey = local.getProperty("GOOGLE_API_KEY") ?: System.getenv("GOOGLE_API_KEY").orEmpty()
        buildConfigField("String", "GOOGLE_API_KEY", "\"$googleApiKey\"")
    }

    // Sin la clave (otro ordenador, CI), la release sale sin firmar.
    val releaseKey = local.getProperty("OTO_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = local.getProperty("OTO_KEYSTORE_PASSWORD")
            keyAlias = local.getProperty("OTO_KEY_ALIAS")
            keyPassword = local.getProperty("OTO_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // La que usa él a diario: la release (R8, sin depurar) en el paquete y con la firma de la debug, donde están sus
        // datos. La debug corre en parte interpretada: abrir el Menú o la letra iba a tirones. Una debug instalada
        // encima (para `run-as`) conserva los datos, y esta encima de esa también.
        create("phone") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
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

    // Las pruebas de JVM llaman a YouTube Music de verdad: lo de Android que toquen por el camino devuelve vacío.
    testOptions.unitTests.isReturnDefaultValues = true

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
    implementation(libs.reorderable)
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
    testImplementation(libs.junit)
    // El org.json de android.jar en las pruebas de JVM es un esqueleto vacío.
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    // Lo que necesita Android de verdad (la caché de audio): en el emulador.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    coreLibraryDesugaring(libs.desugaring)
}
