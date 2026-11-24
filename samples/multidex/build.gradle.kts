// Aplikasi contoh milik proyek untuk menguji ClonApps (Sample Multidex). Tanpa dependensi pihak ketiga.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.clonsapps.sample.multidex"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "com.clonsapps.sample.multidex"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}


// Menghasilkan ±70.000 metode agar D8 benar-benar memecah kode ke classes2.dex (multidex sungguhan).
abstract class GenerateManyMethods : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile.resolve("com/clonsapps/sample/multidex/gen")
        root.deleteRecursively()
        root.mkdirs()
        for (c in 0 until 70) {
            val sb = StringBuilder("package com.clonsapps.sample.multidex.gen;\n\npublic final class Big$c {\n")
            for (m in 0 until 1000) {
                sb.append("    public static int m$m() { return ${c * 1000 + m}; }\n")
            }
            sb.append("}\n")
            root.resolve("Big$c.java").writeText(sb.toString())
        }
    }
}

val generateManyMethods = tasks.register<GenerateManyMethods>("generateManyMethods") {
    outputDir.set(layout.buildDirectory.dir("generated/manyMethods"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateManyMethods, GenerateManyMethods::outputDir)
    }
}
