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
        // GeoTools (Planetiler dependency of tools/packbuilder only).
        maven("https://repo.osgeo.org/repository/release/") {
            content {
                includeGroupByRegex("org[.]geotools.*")
                includeGroupByRegex("org[.]eclipse[.]imagen.*")
                includeGroupByRegex("it[.]geosolutions.*")
                includeGroupByRegex("javax[.]media.*")
            }
        }
    }
}
rootProject.name = "TripNav"
// CI pack builds set TRIPNAV_TOOLS_ONLY so they don't need the Android SDK.
if (System.getenv("TRIPNAV_TOOLS_ONLY") == null) include(":app")
include(":core:protocol", ":tools:packbuilder")
