package com.gradle

class TestInitScriptMetaOptions extends BaseInitScriptTest {

    def "does not log when logging disabled"() {
        when:
        def result = run(testGradle, testConfig().withLogging(false).withCCUDPlugin())

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

}
