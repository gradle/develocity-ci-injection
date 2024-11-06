package com.gradle

import org.gradle.testkit.runner.BuildResult
import spock.lang.Requires

class TestBuildScanCapture extends BaseInitScriptTest {

    @Requires({data.testGradleVersion.compatibleWithCurrentJvm})
    def "does not capture build scan url when init-script not enabled"() {
        given:
        captureBuildScanLinks()

        when:
        def result = run(['help'], testGradleVersion.gradleVersion, [:])

        then:
        buildScanUrlIsNotCaptured(result)

        where:
        testGradleVersion << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradleVersion.compatibleWithCurrentJvm})
    def "can capture build scan url with develocity injection"() {
        given:
        captureBuildScanLinks()

        when:
        def config = TestDevelocityInjection.createTestConfig(mockScansServer.address, testPluginVersion)
        def result = run(['help'], testGradleVersion.gradleVersion, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        [testGradleVersion, testPluginVersion] << versionsToTestForPluginInjection
    }

    @Requires({data.testGradleVersion.compatibleWithCurrentJvm})
    def "can capture build scan url without develocity injection"() {
        given:
        captureBuildScanLinks()
        declareDevelocityPluginApplication(testGradleVersion.gradleVersion)

        when:
        def config = new MinimalTestConfig()
        def result = run(['help'], testGradleVersion.gradleVersion, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        testGradleVersion << ALL_GRADLE_VERSIONS
    }


    @Requires({data.testGradleVersion.compatibleWithCurrentJvm})
    def "can capture build scan url with config-cache enabled"() {
        given:
        captureBuildScanLinks()
        declareDevelocityPluginApplication(testGradleVersion.gradleVersion)

        when:
        def config = new MinimalTestConfig()
        def result = run(['help', '--configuration-cache'], testGradleVersion.gradleVersion, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        when:
        result = run(['help', '--configuration-cache'], testGradleVersion.gradleVersion, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        testGradleVersion << CONFIGURATION_CACHE_GRADLE_VERSIONS
    }

    void buildScanUrlIsCaptured(BuildResult result) {
        def message = "BUILD_SCAN_URL='${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID'"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void buildScanUrlIsNotCaptured(BuildResult result) {
        def message = "BUILD_SCAN_URL='${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID'"
        assert !result.output.contains(message)
    }

    void captureBuildScanLinks() {
        initScriptFile.text = initScriptFile.text.replace('class BuildScanCollector {}', '''
            class BuildScanCollector {
                void captureBuildScanLink(String buildScanUrl) {
                    println "BUILD_SCAN_URL='${buildScanUrl}'"
                }
            }
        ''')
    }

    static class MinimalTestConfig {
        Map<String, String> getEnvVars() {
            Map<String, String> envVars = [
                DEVELOCITY_INJECTION_INIT_SCRIPT_NAME     : "develocity-injection.init.gradle",
            ]
            return envVars
        }
    }

}
