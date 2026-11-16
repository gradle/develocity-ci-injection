plugins {
    id("com.gradle.develocity") version("4.3.1")
    id("com.gradle.common-custom-user-data-gradle-plugin") version("2.8.0")
    id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
}

develocity {
    server = "https://solutions.develocity.cloud"
}

rootProject.name = "develocity-ci-injection"
