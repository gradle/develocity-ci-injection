package com.gradle

import spock.lang.Requires

class TestInitScriptMetaOptions extends BaseInitScriptTest {

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not log debug messages when debug disabled"() {
        when:
        def result = run(testGradle, testConfig().withDebug(false).withCCUDPlugin())

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        outputMissesCcudPluginApplicationViaInitScript(result)

        and:
        outputContainsBuildScanUrl(result)

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

}
