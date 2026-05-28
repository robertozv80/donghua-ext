buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

apply(plugin = "com.lagradost.cloudstream.gradle")

cloudstream {
    repositoryUrl = "https://github.com/robertozv80/donghua-ext"
}