package com.gradle

import org.gradle.testkit.runner.BuildResult
import spock.lang.Requires

class TestBuildScanCapture extends BaseInitScriptTest {

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not capture build scan url when init-script not enabled"() {
        given:
        captureBuildScanLinks()

        when:
        def result = run(['help'], testGradle, [:])

        then:
        buildScanUrlIsNotCaptured(result)

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can capture build scan url with develocity injection"() {
        given:
        captureBuildScanLinks()

        when:
        def config = testConfig(testDvPlugin.version)
        def result = run(['help'], testGradle, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        [testGradle, testDvPlugin] << versionsToTestForPluginInjection
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can capture build scan url without develocity injection"() {
        given:
        captureBuildScanLinks()
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = new MinimalTestConfig()
        def result = run(['help'], testGradle, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin()
    }


    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can capture build scan url with config-cache enabled"() {
        given:
        captureBuildScanLinks()
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = new MinimalTestConfig()
        def result = run(['help', '--configuration-cache'], testGradle, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        when:
        result = run(['help', '--configuration-cache'], testGradle, config.envVars)

        then:
        buildScanUrlIsCaptured(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin(CONFIGURATION_CACHE_GRADLE_VERSIONS)
            .findAll { gradleVersion, dvPlugin -> dvPlugin.isCompatibleWithConfigurationCache() }
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
