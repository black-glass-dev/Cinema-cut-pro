pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Prefer the public Google-hosted Central mirror for Robolectric's runtime artifacts;
        // keep Maven Central as the fallback for anything the mirror does not carry.
        maven {
            name = "googleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}

rootProject.name = "Cinema Cut Pro"
include(":app")
