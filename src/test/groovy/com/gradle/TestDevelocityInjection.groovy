package com.gradle

import spock.lang.Requires

class TestDevelocityInjection extends BaseInitScriptTest {
    static final List<TestGradleVersion> CCUD_COMPATIBLE_GRADLE_VERSIONS = ALL_GRADLE_VERSIONS - [GRADLE_3_X]

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not apply Develocity plugins when not requested"() {
        when:
        def result = run([], testGradle)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not override Develocity plugin when already defined in project"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def result = run(testGradle, testConfig())

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "applies Develocity plugin via init script when not defined in project"() {
        when:
        def result = run(testGradle, testConfig(testDvPlugin.version))

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "applies Develocity and CCUD plugins via init script when not defined in project"() {
        when:
        def ccudPluginVersion = testDvPlugin.compatibleCCUDVersion
        def result = run(testGradle, testConfig(testDvPlugin.version).withCCUDPlugin(ccudPluginVersion))

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputContainsCcudPluginApplicationViaInitScript(result, ccudPluginVersion)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection(CCUD_COMPATIBLE_GRADLE_VERSIONS)
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "applies CCUD plugin via init script where Develocity plugin already applied"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def ccudPluginVersion = testDvPlugin.compatibleCCUDVersion
        def result = run(testGradle, testConfig().withCCUDPlugin(ccudPluginVersion))

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputContainsCcudPluginApplicationViaInitScript(result, ccudPluginVersion)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin(CCUD_COMPATIBLE_GRADLE_VERSIONS)
            // Ignore test for old versions of plugin where no CCUD works.
            .findAll {testGradle, testDvPlugin -> testDvPlugin.compatibleCCUDVersion != null}
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not override CCUD plugin when already defined in project"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin, testDvPlugin.compatibleCCUDVersion)

        when:
        def result = run(testGradle, testConfig().withCCUDPlugin(CCUD_PLUGIN_VERSION))

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin(CCUD_COMPATIBLE_GRADLE_VERSIONS)
            // Ignore test for old versions of plugin where no CCUD works.
            .findAll {testGradle, testDvPlugin -> testDvPlugin.compatibleCCUDVersion != null}
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "ignores Develocity URL and allowUntrustedServer when Develocity plugin is already defined in project"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = testConfig().withServer(URI.create('https://develocity-server.invalid'))
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures Develocity URL and allowUntrustedServer when Develocity plugin is applied by the init script"() {
        when:
        def config = testConfig(testDvPlugin.version).withServer(mockScansServer.address)
        def result = run(testGradle, config)

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputContainsDevelocityConnectionInfo(result, mockScansServer.address.toString(), true)
        outputMissesCcudPluginApplicationViaInitScript(result)
        outputContainsPluginRepositoryInfo(result, 'https://plugins.gradle.org/m2')

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure capturing file fingerprints when Develocity plugin is applied by the init script"() {
        when:
        def config = testConfig(testDvPlugin.version).withCaptureFileFingerprints()
        def result = run(testGradle, config)

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputContainsDevelocityConnectionInfo(result, mockScansServer.address.toString(), true)
        outputMissesCcudPluginApplicationViaInitScript(result)
        if (testGradle.gradleVersion > GRADLE_5) {
            outputCaptureFileFingerprints(result, true)
        }

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can accept Gradle Terms of Use when Develocity plugin is applied by the init script"() {
        when:
        def config = testConfig(testDvPlugin.version).withAcceptGradleTermsOfUse()
        def result = run(testGradle, config)

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputContainsDevelocityConnectionInfo(result, mockScansServer.address.toString(), true)
        outputMissesCcudPluginApplicationViaInitScript(result)
        outputContainsPluginRepositoryInfo(result, 'https://plugins.gradle.org/m2')

        and:
        outputContainsAcceptingGradleTermsOfUse(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can accept Gradle Terms of Use in project with DV plugin already defined"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = testConfig().withAcceptGradleTermsOfUse().withoutDevelocityPluginVersion()
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsAcceptingGradleTermsOfUse(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "enforces Develocity URL and allowUntrustedServer in project with DV plugin already defined if enforce url parameter is enabled"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin, null, URI.create('https://develocity-server.invalid'))

        when:
        def config = testConfig().withServer(mockScansServer.address, true)
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputEnforcesDevelocityUrl(result, mockScansServer.address.toString(), true)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "enforces Develocity URL and allowUntrustedServer in project with DV plugin already defined if enforce url parameter is enabled and no DV plugin version configured"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin, null, URI.create('https://develocity-server.invalid'))

        when:
        def config = testConfig().withServer(mockScansServer.address, true).withoutDevelocityPluginVersion()
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputEnforcesDevelocityUrl(result, mockScansServer.address.toString(), true)

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure uploadInBackground when Develocity plugin is applied by the init script"() {
        when:
        def result = run(testGradle, testConfig(testDvPlugin.version).withUploadInBackground(true))

        then:
        if (testGradle.gradleVersion < GRADLE_5) {
            // Gradle 4.x and earlier will always inject build-scan-plugin 1.16 which doesn't have uploadInBackground
            outputMissesUploadInBackground(result)
        } else {
            outputContainsUploadInBackground(result, true)
        }

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure uploadInBackground when Develocity plugin already applied"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin, null, mockScansServer.address)

        when:
        def result = run(testGradle, testConfig().withoutDevelocityPluginVersion())

        then:
        if (testDvPlugin.compatibleWithUploadInBackground) {
            outputContainsUploadInBackground(result, true)
        } else {
            outputMissesUploadInBackground(result)
        }

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure alternative repository for plugins when Develocity plugin is applied by the init script"() {
        when:
        def config = testConfig().withPluginRepository(new URI('https://plugins.grdev.net/m2'))
        def result = run(testGradle, config)

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion)
        outputContainsDevelocityConnectionInfo(result, mockScansServer.address.toString(), true)
        outputMissesCcudPluginApplicationViaInitScript(result)
        outputContainsPluginRepositoryInfo(result, 'https://plugins.grdev.net/m2')

        and:
        outputContainsBuildScanUrl(result)

        where:
        // Plugin repository is unrelated to DV plugin, so only test with latest DV plugin version
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure alternative repository for plugins with credentials when Develocity plugin is applied by the init script"() {
        when:
        def config = testConfig().withPluginRepository(new URI('https://plugins.grdev.net/m2')).withPluginRepositoryCredentials("john", "doe")
        def result = run(testGradle, config)

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion)
        outputContainsDevelocityConnectionInfo(result, mockScansServer.address.toString(), true)
        outputMissesCcudPluginApplicationViaInitScript(result)
        outputContainsPluginRepositoryInfo(result, 'https://plugins.grdev.net/m2', true)

        and:
        outputContainsBuildScanUrl(result)

        where:
        // Plugin repository is unrelated to DV plugin, so only test with latest DV plugin version
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "stops gracefully when requested CCUD plugin version is <1.7"() {
        when:
        def config = testConfig().withCCUDPlugin("1.6.6")
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)
        result.output.contains('Common Custom User Data Gradle plugin must be at least 1.7. Configured version is 1.6.6.')

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "stops gracefully when requested DV plugin version is < 3.6.4"() {
        when:
        def config = testConfig('3.6.3')
        def result = run(testGradle, config)

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)
        result.output.contains('Develocity Gradle plugin must be at least 3.6.4. Configured version is 3.6.3.')

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure Develocity via CCUD system property overrides when plugins are injected via init script"() {
        when:
        def config = testConfig().withCCUDPlugin().withServer(URI.create('https://develocity-server.invalid'))
        def result = run(testGradle, config, ["help", "-Ddevelocity.url=${mockScansServer.address}".toString()])

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion)
        outputContainsCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        testGradle << CCUD_COMPATIBLE_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can apply Develocity plugin using system properties via init script when org.gradle.jvmargs are defined"() {
        given:
        gradleProperties.text = 'org.gradle.jvmargs=-Dfile.encoding=UTF-8'
        usingSystemProperties = true

        when:
        def result = run(testGradle, testConfig())

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion)

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "init script is configuration cache compatible"() {
        when:
        def config = testConfig().withCCUDPlugin()
        def result = run(testGradle, config, ["help", "--configuration-cache"])

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion)
        outputContainsCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        when:
        result = run(testGradle, config, ["help", "--configuration-cache"])

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        testGradle << CONFIGURATION_CACHE_GRADLE_VERSIONS
    }

}
