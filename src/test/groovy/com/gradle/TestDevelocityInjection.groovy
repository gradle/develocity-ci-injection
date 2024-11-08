package com.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.util.GradleVersion
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
            // TODO: There is a bug in the init-script, trying to set `gradleEnterprise.server` does not work for GE plugin `v3.0`
            .findAll {testGradle, testDvPlugin -> !(testDvPlugin.pluginId.id == 'com.gradle.enterprise' && testDvPlugin.version == '3.0')}
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

    void outputContainsBuildScanUrl(BuildResult result) {
        def message = "Publishing build scan...\n${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputContainsDevelocityPluginApplicationViaInitScript(BuildResult result, GradleVersion gradleVersion, String pluginVersion = DEVELOCITY_PLUGIN_VERSION) {
        def pluginApplicationLogMsgGradle4 = "Applying com.gradle.scan.plugin.BuildScanPlugin with version 1.16 via init script"
        def pluginApplicationLogMsgGEPlugin = "Applying com.gradle.enterprise.gradleplugin.GradleEnterprisePlugin with version ${pluginVersion} via init script"
        def pluginApplicationLogMsgDVPlugin = "Applying com.gradle.develocity.agent.gradle.DevelocityPlugin with version ${pluginVersion} via init script"

        def isGEPluginVersion = GradleVersion.version(pluginVersion) < GradleVersion.version("3.17")

        if (gradleVersion < GRADLE_5 || (gradleVersion < GRADLE_6 && isGEPluginVersion)) {
            assert result.output.contains(pluginApplicationLogMsgGradle4)
            assert 1 == result.output.count(pluginApplicationLogMsgGradle4)
            assert !result.output.contains(pluginApplicationLogMsgGEPlugin)
            assert !result.output.contains(pluginApplicationLogMsgDVPlugin)
        } else if (isGEPluginVersion) {
            assert result.output.contains(pluginApplicationLogMsgGEPlugin)
            assert 1 == result.output.count(pluginApplicationLogMsgGEPlugin)
            assert !result.output.contains(pluginApplicationLogMsgGradle4)
            assert !result.output.contains(pluginApplicationLogMsgDVPlugin)
        } else {
            assert result.output.contains(pluginApplicationLogMsgDVPlugin)
            assert 1 == result.output.count(pluginApplicationLogMsgDVPlugin)
            assert !result.output.contains(pluginApplicationLogMsgGradle4)
            assert !result.output.contains(pluginApplicationLogMsgGEPlugin)
        }
    }

    void outputMissesDevelocityPluginApplicationViaInitScript(BuildResult result) {
        def pluginApplicationLogMsgGradle4 = "Applying com.gradle.scan.plugin.BuildScanPlugin"
        def pluginApplicationLogMsgGradle5AndHigher = "Applying com.gradle.develocity.agent.gradle.DevelocityPlugin"
        assert !result.output.contains(pluginApplicationLogMsgGradle4)
        assert !result.output.contains(pluginApplicationLogMsgGradle5AndHigher)
    }

    void outputContainsCcudPluginApplicationViaInitScript(BuildResult result, String ccudPluginVersion = CCUD_PLUGIN_VERSION) {
        def pluginApplicationLogMsg = "Applying com.gradle.CommonCustomUserDataGradlePlugin with version ${ccudPluginVersion} via init script"
        assert result.output.contains(pluginApplicationLogMsg)
        assert 1 == result.output.count(pluginApplicationLogMsg)
    }

    void outputMissesCcudPluginApplicationViaInitScript(BuildResult result) {
        def pluginApplicationLogMsg = "Applying com.gradle.CommonCustomUserDataGradlePlugin"
        assert !result.output.contains(pluginApplicationLogMsg)
    }

    void outputContainsDevelocityConnectionInfo(BuildResult result, String develocityUrl, boolean develocityAllowUntrustedServer) {
        def develocityConnectionInfo = "Connection to Develocity: $develocityUrl, allowUntrustedServer: $develocityAllowUntrustedServer"
        assert result.output.contains(develocityConnectionInfo)
        assert 1 == result.output.count(develocityConnectionInfo)
    }

    void outputCaptureFileFingerprints(BuildResult result, boolean captureFileFingerprints) {
        def captureFileFingerprintsInfo = "Setting captureFileFingerprints: $captureFileFingerprints"
        assert result.output.contains(captureFileFingerprintsInfo)
        assert 1 == result.output.count(captureFileFingerprintsInfo)
    }

    void outputContainsPluginRepositoryInfo(BuildResult result, String gradlePluginRepositoryUrl, boolean withCredentials = false) {
        def repositoryInfo = "Develocity plugins resolution: ${gradlePluginRepositoryUrl}"
        assert result.output.contains(repositoryInfo)
        assert 1 == result.output.count(repositoryInfo)

        if (withCredentials) {
            def credentialsInfo = "Using credentials for plugin repository"
            assert result.output.contains(credentialsInfo)
            assert 1 == result.output.count(credentialsInfo)
        }
    }

    void outputEnforcesDevelocityUrl(BuildResult result, String develocityUrl, boolean develocityAllowUntrustedServer) {
        def enforceUrl = "Enforcing Develocity: $develocityUrl, allowUntrustedServer: $develocityAllowUntrustedServer"
        assert result.output.contains(enforceUrl)
        assert 1 == result.output.count(enforceUrl)
    }

    private BuildResult run(TestGradleVersion testGradle, DvInjectionTestConfig config, List<String> args = ["help"]) {
        return run(args, testGradle, config.envVars)
    }

    DvInjectionTestConfig testConfig(String develocityPluginVersion = DEVELOCITY_PLUGIN_VERSION) {
        createTestConfig(mockScansServer.address, develocityPluginVersion)
    }

    static DvInjectionTestConfig createTestConfig(URI serverAddress, String develocityPluginVersion = DEVELOCITY_PLUGIN_VERSION) {
        new DvInjectionTestConfig(serverAddress, develocityPluginVersion.toString())
    }

    static class DvInjectionTestConfig {
        String serverUrl
        boolean enforceUrl = false
        String ccudPluginVersion = null
        String pluginRepositoryUrl = null
        String pluginRepositoryUsername = null
        String pluginRepositoryPassword = null
        boolean captureFileFingerprints = false
        String develocityPluginVersion

        DvInjectionTestConfig(URI serverAddress, String develocityPluginVersion) {
            this.serverUrl = serverAddress.toString()
            this.develocityPluginVersion = develocityPluginVersion
        }

        DvInjectionTestConfig withCCUDPlugin(String version = CCUD_PLUGIN_VERSION) {
            ccudPluginVersion = version
            return this
        }

        DvInjectionTestConfig withServer(URI url, boolean enforceUrl = false) {
            serverUrl = url.toASCIIString()
            this.enforceUrl = enforceUrl
            return this
        }

        DvInjectionTestConfig withPluginRepository(URI pluginRepositoryUrl) {
            this.pluginRepositoryUrl = pluginRepositoryUrl
            return this
        }

        DvInjectionTestConfig withCaptureFileFingerprints() {
            this.captureFileFingerprints = true
            return this
        }

        DvInjectionTestConfig withPluginRepositoryCredentials(String pluginRepoUsername, String pluginRepoPassword) {
            this.pluginRepositoryUsername = pluginRepoUsername
            this.pluginRepositoryPassword = pluginRepoPassword
            return this
        }

        Map<String, String> getEnvVars() {
            Map<String, String> envVars = [
                DEVELOCITY_INJECTION_INIT_SCRIPT_NAME     : "develocity-injection.init.gradle",
                DEVELOCITY_INJECTION_ENABLED              : "true",
                DEVELOCITY_URL                            : serverUrl,
                DEVELOCITY_ALLOW_UNTRUSTED_SERVER         : "true",
                DEVELOCITY_PLUGIN_VERSION                 : develocityPluginVersion,
                DEVELOCITY_BUILD_SCAN_UPLOAD_IN_BACKGROUND: "true", // Need to upload in background since our Mock server doesn't cope with foreground upload
                DEVELOCITY_AUTO_INJECTION_CUSTOM_VALUE    : 'gradle-actions'
            ]
            if (enforceUrl) envVars.put("DEVELOCITY_ENFORCE_URL", "true")
            if (ccudPluginVersion != null) envVars.put("DEVELOCITY_CCUD_PLUGIN_VERSION", ccudPluginVersion)
            if (pluginRepositoryUrl != null) envVars.put("GRADLE_PLUGIN_REPOSITORY_URL", pluginRepositoryUrl)
            if (pluginRepositoryUsername != null) envVars.put("GRADLE_PLUGIN_REPOSITORY_USERNAME", pluginRepositoryUsername)
            if (pluginRepositoryPassword != null) envVars.put("GRADLE_PLUGIN_REPOSITORY_PASSWORD", pluginRepositoryPassword)
            if (captureFileFingerprints) envVars.put("DEVELOCITY_CAPTURE_FILE_FINGERPRINTS", "true")
            return envVars
        }
    }
}
