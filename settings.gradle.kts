pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Coach"
include(":shared:domain", ":shared:protocol", ":shared:client", ":shared:ui")
include(":androidApp", ":desktopApp")
include(":server:app", ":server:core", ":server:store", ":server:content", ":server:ai")
include(":tools:migrate-deeptutor")
