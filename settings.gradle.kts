pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val consumerPomOnly =
    providers.gradleProperty("procwright.consumerPomOnly").map(String::toBoolean).orElse(false)
val consumerRepository = providers.gradleProperty("procwright.consumerRepository").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        consumerRepository?.let { repository ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "ProcwrightConsumer"
                        url = uri(repository)
                        if (consumerPomOnly.get()) {
                            metadataSources {
                                mavenPom()
                                ignoreGradleMetadataRedirection()
                            }
                        }
                    }
                }
                filter { includeGroup("io.github.ulviar") }
            }
        }
        mavenCentral {
            if (consumerPomOnly.get()) {
                metadataSources {
                    mavenPom()
                    ignoreGradleMetadataRedirection()
                }
            }
        }
    }
}

rootProject.name = "procwright"

include("procwright-kotlin")

include("procwright-integrations")

include("procwright-test-cli")

include("procwright-consumer-examples")

include("procwright-integrations-consumer-example")

include("procwright-kotlin-consumer-example")
