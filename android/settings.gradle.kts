pluginManagement {
    repositories {
        google()
        maven { url = uri(providers.gradleProperty("centralRepositoryUrl").getOrElse("https://repo.maven.apache.org/maven2")) }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri(providers.gradleProperty("centralRepositoryUrl").getOrElse("https://repo.maven.apache.org/maven2")) }
    }
}
rootProject.name = "SmartFittingMirror"
include(":app", ":core-contracts", ":core-vision", ":core-network", ":feature-tryon")
