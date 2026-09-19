import java.util.Base64
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

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
                        if (
                            repository.startsWith(
                                "https://central.sonatype.com/api/v1/publisher/deployment/"
                            )
                        ) {
                            credentials(HttpHeaderCredentials::class) {
                                name = "Authorization"
                                val username =
                                    providers.gradleProperty("mavenCentralUsername").get()
                                val password =
                                    providers.gradleProperty("mavenCentralPassword").get()
                                value =
                                    "Bearer " +
                                        Base64.getEncoder()
                                            .encodeToString(
                                                "$username:$password".toByteArray(Charsets.UTF_8)
                                            )
                            }
                            authentication { create<HttpHeaderAuthentication>("header") }
                        }
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
