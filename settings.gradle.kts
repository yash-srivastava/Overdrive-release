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
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // sing-box library repository
        maven { url = uri("https://maven.pkg.github.com/SagerNet/sing-box") }
    }
}

rootProject.name = "Overdrive"
include(":app")
// Official builds: the companion launcher lives in the private overlay.
if (file("private/launcher/build.gradle.kts").isFile) {
    include(":launcher")
    project(":launcher").projectDir = file("private/launcher")
}
include(":dilink-probe")
 