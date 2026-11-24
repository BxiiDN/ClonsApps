// Mesin cloning: Java murni, berjalan di Android (dan di JVM untuk pengujian).
// Tidak ada executable desktop (aapt/apktool/zipalign) yang dikemas.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.apksig)
    testImplementation(libs.junit)
}

val sampleModules = listOf("simple", "multidex", "nativelib", "provider")

tasks.test {
    // Pengujian memakai APK contoh yang dibangun AGP sungguhan.
    dependsOn(sampleModules.map { ":samples:$it:assembleDebug" })
    systemProperty("samples.dir", rootProject.layout.projectDirectory.dir("samples").asFile.absolutePath)
    val split = providers.gradleProperty("sampleSplitApks").orNull
    if (split != null) {
        systemProperty("sample.split.apks", split)
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
