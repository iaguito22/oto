@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // InnerTubeX solo se publica en JitPack; el resto nunca se busca allí.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Oto"
include(":app")
include(":innertube")
