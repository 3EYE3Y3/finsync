rootProject.name = "finsync"

pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

include(":engine", ":cli", ":app")
