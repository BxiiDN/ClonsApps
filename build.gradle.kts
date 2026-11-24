// AGP 9 memakai Kotlin bawaan (built-in Kotlin) untuk modul Android. Plugin kotlin-jvm dideklarasikan
// (tanpa diterapkan) agar AGP dan plugin Compose memakai versi Kotlin Gradle Plugin yang sama.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
