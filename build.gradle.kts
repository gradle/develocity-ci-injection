plugins {
    groovy
    `jvm-test-suite`
}

group = "com.gradle"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useSpock("2.3-groovy-3.0")

            dependencies {
                implementation(gradleTestKit())
                implementation("io.ratpack:ratpack-groovy-test:1.9.0") {
                    exclude(group = "org.codehaus.groovy", module = "groovy-all")
                }
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-smile:2.20.0")
            }
        }
    }
}

tasks.register<Copy>("promote") {
    val version: String = project.property("version") as String
    inputs.property("version", version)

    from("src/main/resources/develocity-injection.init.gradle") {
        filter { line: String ->
            line.replace("<<version>>", version, false)
        }
    }
    into("reference")
}

// Exposes the init script as a resolvable artifact when this project is used as
// an included build.
configurations.consumable("develocityInjectionScript") {
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named("develocity-injection-script"))
    outgoing.artifact(tasks.named<ProcessResources>("processResources")
        .map { it.destinationDir.resolve("develocity-injection.init.gradle") })
}
