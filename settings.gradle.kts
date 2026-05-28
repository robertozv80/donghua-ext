pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "donghua-ext"

include(":MundoDonghuaProvider")
include(":SeriesDonghuaProvider")
include(":DonghuaLifeProvider")
include(":AnimeGratisProvider")