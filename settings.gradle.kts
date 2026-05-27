pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "procwright"

include("procwright-kotlin")

include("procwright-integrations")

include("procwright-comparison")

include("procwright-test-cli")

include("procwright-consumer-examples")
