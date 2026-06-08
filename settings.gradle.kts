cat > settings.gradle.kts << 'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.lagradost.cloudstream3.gradle") {
                useModule("com.github.recloudstream:gradle:${requested.version}")
            }
        }
    }
}

rootProject.name = "donghua-ext"

include(":MundoDonghuaProvider")
include(":SeriesDonghuaProvider")
include(":DonghuaLifeProvider")
include(":AnimeGratisProvider")
include(":DonghuaWorldProvider")
EOF
