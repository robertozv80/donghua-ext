rootProject.name = "donghua-ext"

// Auto-discover provider subprojects
File(rootProject.projectDir, ".").listFiles()?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }?.forEach {
    include(it.name)
}