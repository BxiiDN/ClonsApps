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
        google()
        mavenCentral()
    }
}

rootProject.name = "ClonsApps"
include(
    ":engine",
    ":app",
    ":samples:simple",
    ":samples:multidex",
    ":samples:nativelib",
    ":samples:provider",
    ":samples:split",
)
