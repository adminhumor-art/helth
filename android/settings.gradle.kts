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

rootProject.name = "Sladkaya"
include(":app")
include(":core:model")
include(":core:sensor")
include(":core:data")
include(":sensor:simulator")
include(":sensor:sibionics")
include(":sensor:sibionics-algorithm")
include(":sensor:sibionics-datahandle")
