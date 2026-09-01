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

rootProject.name = "Jagdamba Smart Pathshala"

include(":core")
include(":tv")
// :mobile is cut from the 2-day sprint. It has a build file but no manifest or
// sources, so including it fails the build. Uncomment when you actually start it.
// include(":mobile")
