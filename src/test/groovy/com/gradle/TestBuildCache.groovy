package com.gradle

import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Requires

import static com.gradle.BaseInitScriptTest.DvPluginId.*

class TestBuildCache extends BaseInitScriptTest {
    def setup() {
        declareJavaProject()
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache when not requested"() {
        when:
        def result = run(testGradle, testConfig(), buildCacheArgs())

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        !result.output.contains("build cache not configured")
        remoteBuildCacheRequests.empty

        where:
        testGradle << ALL_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures remote build cache when Develocity plugin is applied by the init script"() {
        when:
        def result = run(testGradle, testConfig(testDvPlugin.version).withRemoteBuildCache(true, true), buildCacheArgs())

        then:
        outputContainsDevelocityPluginApplicationViaInitScript(result, testGradle.gradleVersion, testDvPlugin.version)
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)
        remoteBuildCacheRequests*.method == ['GET', 'PUT']

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForPluginInjection(BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS)
            .findAll { testGradle, testDvPlugin -> testDvPlugin.compatibleWithBuildCacheConnector }
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures remote build cache when Develocity plugin is already applied"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def result = run(testGradle, testConfig().withRemoteBuildCache(true, true), buildCacheArgs())

        then:
        outputMissesDevelocityPluginApplicationViaInitScript(result)
        // Before 3.12, the connector defaults allowUntrustedServer to false instead of following the plugin
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true, null, true, testDvPlugin.pluginVersionAtLeast('3.12') ? null : false)
        remoteBuildCacheRequests*.method == ['GET', 'PUT']

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin(BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS)
            .findAll { testGradle, testDvPlugin -> testDvPlugin.compatibleWithBuildCacheConnector }
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not push to remote build cache unless push is enabled"() {
        when:
        def result = run(testGradle, testConfig().withRemoteBuildCache(true), buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, false)
        remoteBuildCacheRequests*.method == ['GET']

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure remote build cache server"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withRemoteBuildCacheLocation(buildCacheNodeServer() + (trailingSlash ? '/' : ''))
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, buildCacheNodeServer(), true, true)
        buildCacheNodeRequests*.method == ['GET', 'PUT']
        buildCacheNodeRequests*.path.every { it.startsWith('cache/') }
        remoteBuildCacheRequests.empty

        and:
        outputContainsBuildScanUrl(result)

        where:
        [testGradle, trailingSlash] << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS.collect { [it, false] } + [[GRADLE_8_X, true]]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can configure remote build cache path"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withRemoteBuildCacheLocation(buildCacheNodeServer(), 'node-cache')
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, buildCacheNodeServer(), true, true, 'node-cache')
        buildCacheNodeRequests*.method == ['GET', 'PUT']
        buildCacheNodeRequests*.path.every { it.startsWith('node-cache/') }
        remoteBuildCacheRequests.empty

        where:
        testGradle << [GRADLE_6_X, GRADLE_8_X]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "fails the build when remote build cache server is invalid"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withRemoteBuildCacheLocation(buildCacheNodeServer() + '/cache')
        def result = createRunner(buildCacheArgs(), testGradle.gradleVersion, config.envVars).buildAndFail()

        then:
        result.output.contains("should not contain a path component")

        where:
        testGradle << [GRADLE_6_X, GRADLE_8_X]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not use remote build cache location unless remote build cache is enabled"() {
        when:
        def config = testConfig().withRemoteBuildCacheLocation('edge.example.com', 'build cache').withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsLocalBuildCacheConfiguration(result, false, true)
        !result.output.contains("build cache not configured")

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.SUCCESS

        where:
        testGradle << [GRADLE_8_X]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "remote build cache uses access key configured for Develocity plugin"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true)
        def result = run(buildCacheArgs(), testGradle, config.envVars + [DEVELOCITY_ACCESS_KEY: 'localhost=test-access-key'])

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)
        remoteBuildCacheRequests*.method == ['GET', 'PUT']
        remoteBuildCacheRequests*.authorization.every { it == 'Bearer test-access-key' }

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not use remote build cache over plain HTTP unless insecure protocol is allowed"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withRemoteBuildCacheAllowInsecureProtocol(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true, null, false)
        result.output.contains("unencrypted HTTP")
        remoteBuildCacheRequests.empty

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can allow untrusted remote build cache server"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = testConfig().withRemoteBuildCache(true).withRemoteBuildCacheAllowUntrustedServer()
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, false, null, true, true)
        remoteBuildCacheRequests*.method == ['GET']

        where:
        testGradle | testDvPlugin
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache unless enabled is set"() {
        given:
        if (withProjectRemoteBuildCache) {
            declareHttpBuildCache()
        }

        when:
        def config = testConfig().withRemoteBuildCache(null, true).withRemoteBuildCacheLocation(buildCacheNodeServer()).withRemoteBuildCacheAllowUntrustedServer().withLocalBuildCache(null, false)
        if (enforce) {
            config.withEnforceBuildCache()
        }
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        !result.output.contains("build cache not configured")
        buildCacheNodeRequests*.method == (withProjectRemoteBuildCache ? ['GET'] : [])
        buildCacheNodeRequests*.path.every { it.startsWith('node-cache/') }
        remoteBuildCacheRequests.empty

        where:
        testGradle | enforce | withProjectRemoteBuildCache
        GRADLE_6_X | false   | false
        GRADLE_8_X | false   | false
        GRADLE_8_X | true    | false
        GRADLE_8_X | true    | true
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can disable remote build cache"() {
        when:
        def result = run(testGradle, testConfig().withRemoteBuildCache(false, true), buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', false, true)
        remoteBuildCacheRequests.empty

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures remote and local build cache together"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)
        outputContainsLocalBuildCacheConfiguration(result, false, true)
        !result.output.contains("build cache not configured")
        remoteBuildCacheRequests*.method == ['GET', 'PUT']

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE
        remoteBuildCacheRequests*.method == ['GET', 'PUT', 'GET']

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache when Develocity plugin has no server"() {
        given:
        declareDvPluginApplicationWithoutServer(testDvPlugin)
        if (enforce) {
            declareHttpBuildCache()
        }

        when:
        def config = testConfig().withRemoteBuildCache(true, true).withLocalBuildCache(false)
        if (enforce) {
            config.withEnforceBuildCache()
        }
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputSkipsRemoteBuildCache(result, "no Develocity server is configured")
        outputSkipsLocalBuildCacheWithRemoteBuildCache(result)
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        buildCacheNodeRequests*.method == (enforce ? ['GET'] : [])
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config.withDebug(false), buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE
        !result.output.contains("build cache not configured")

        where:
        testGradle | testDvPlugin                                   | enforce
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | false
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | false
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | true
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')           | false
        GRADLE_6_X | dvPlugin(GRADLE_ENTERPRISE, '3.11.1')           | false
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures remote build cache when Develocity plugin has no server and #description"() {
        given:
        declareDvPluginApplicationWithoutServer(testDvPlugin)

        when:
        def config = testConfig().withRemoteBuildCache(enabled, true).withRemoteBuildCacheLocation(server ? develocityServer() : null)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, server ? develocityServer() : 'Develocity server', enabled, true)
        remoteBuildCacheRequests*.method == (enabled ? ['GET', 'PUT'] : [])

        where:
        description                         | enabled | server
        'remote build cache server is set'  | true    | true
        'remote build cache is disabled'    | false   | false

        testGradle = GRADLE_8_X
        testDvPlugin = dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache when project configures remote build cache"() {
        given:
        if (testDvPlugin) {
            declareDvPluginApplication(testGradle, testDvPlugin)
        }
        if (projectRemoteBuildCache == 'http') {
            declareHttpBuildCache()
        } else {
            declareBuildCacheConnector(testDvPlugin, projectRemoteBuildCache)
        }
        // The gradleEnterprise extension is deprecated in the Develocity Gradle plugin
        if (testDvPlugin?.pluginId == DEVELOCITY && projectRemoteBuildCache == 'gradleEnterprise') {
            allowDevelocityDeprecationWarning = true
        }

        when:
        def config = testConfig().withLocalBuildCache(false)
        if (withRemoteInputs) {
            config.withRemoteBuildCache(true, true).withRemoteBuildCacheLocation(develocityServer())
        }
        def result = run(testGradle, config, buildCacheArgs())

        then:
        result.output.count("Remote build cache not configured because a remote build cache is already configured") == (withRemoteInputs ? 1 : 0)
        result.output.count("Local build cache not configured to avoid leaving the build cache partially configured") == (withRemoteInputs ? 1 : 0)
        result.output.count("Local build cache not configured because a remote build cache is already configured") == (withRemoteInputs ? 0 : 1)
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        buildCacheNodeRequests*.method == ['GET']
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config.withDebug(false), buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE
        !result.output.contains("build cache not configured")

        where:
        [testGradle, testDvPlugin, projectRemoteBuildCache, withRemoteInputs] << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS.collect { [it, null, 'http', true] } + [
            [GRADLE_8_X, null, 'http', false],
            [GRADLE_6_X, dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION), 'develocity', true],
            [GRADLE_8_X, dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION), 'develocity', true],
            [GRADLE_8_X, dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION), 'develocity', false],
            [GRADLE_8_X, dvPlugin(DEVELOCITY, '3.17'), 'gradleEnterprise', true],
            [GRADLE_8_X, dvPlugin(GRADLE_ENTERPRISE, '3.16.2'), 'gradleEnterprise', true],
        ]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "replaces another type of remote build cache configured in project when build cache is enforced"() {
        given:
        declareHttpBuildCache()

        when:
        def result = run(testGradle, testConfig().withEnforceBuildCache().withRemoteBuildCache(true, true), buildCacheArgs())

        then:
        result.output.contains("Replacing the existing remote build cache with the Develocity Build Cache connector")
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)
        remoteBuildCacheRequests*.method == ['GET', 'PUT']
        buildCacheNodeRequests.empty

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not cause Develocity deprecation warning when replacing another type of remote build cache"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)
        declareHttpBuildCache()

        when:
        // The run fails on a Develocity deprecation warning
        def result = run(testGradle, testConfig().withEnforceBuildCache().withRemoteBuildCache(true, true), buildCacheArgs())

        then:
        result.output.contains("Replacing the existing remote build cache with the Develocity Build Cache connector")
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)

        where:
        testGradle << [GRADLE_6_X, GRADLE_8_X]
        testDvPlugin = dvPlugin(DEVELOCITY, '3.17')
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "resets connector configured in project to default values when build cache is enforced"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)
        declareBuildCacheConnector(testDvPlugin, extension)
        settingsFile << "buildCache.remote.allowUntrustedServer = true\n"
        // The gradleEnterprise extension is deprecated in the Develocity Gradle plugin
        if (testDvPlugin.pluginId == DEVELOCITY && extension == 'gradleEnterprise') {
            allowDevelocityDeprecationWarning = true
        }

        when:
        def config = testConfig().withEnforceBuildCache().withRemoteBuildCache(true)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        // The connector of the gradleEnterprise extension is replaced by the connector of the develocity extension when both exist
        result.output.contains("Replacing the existing remote build cache with the Develocity Build Cache connector") == replaced
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, false)
        remoteBuildCacheRequests*.method == ['GET']
        buildCacheNodeRequests.empty

        where:
        testGradle | testDvPlugin                                   | extension          | replaced
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | 'develocity'       | false
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | 'develocity'       | false
        GRADLE_8_X | dvPlugin(DEVELOCITY, '3.17')                    | 'gradleEnterprise' | true
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')           | 'gradleEnterprise' | false
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "configures connector configured in project from inputs when build cache is enforced"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)
        declareBuildCacheConnector(testDvPlugin)

        when:
        def config = testConfig().withEnforceBuildCache().withRemoteBuildCache(enabled, true).withRemoteBuildCacheLocation(develocityServer())
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, develocityServer(), enabled, true)
        remoteBuildCacheRequests*.method == (enabled ? ['GET', 'PUT'] : [])
        buildCacheNodeRequests.empty

        where:
        testGradle | testDvPlugin                                   | enabled
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | true
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | true
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION) | false
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')           | true
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "uses remote build cache path when build cache is enforced"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)
        declareBuildCacheConnector(testDvPlugin)

        when:
        def config = testConfig().withEnforceBuildCache().withRemoteBuildCache(true).withRemoteBuildCacheLocation(null, 'other-cache')
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, false, 'other-cache')
        buildCacheNodeRequests.empty

        where:
        testGradle | testDvPlugin
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "keeps remote build cache configured in project when build cache is enforced with only local build cache inputs"() {
        given:
        if (testDvPlugin) {
            declareDvPluginApplication(testGradle, testDvPlugin)
            declareBuildCacheConnector(testDvPlugin)
        } else {
            declareHttpBuildCache()
        }

        when:
        def config = testConfig().withEnforceBuildCache().withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsLocalBuildCacheConfiguration(result, false, true)
        outputMissesRemoteBuildCacheConfiguration(result)
        !result.output.contains("Replacing the existing remote build cache")
        !result.output.contains("build cache not configured")
        buildCacheNodeRequests*.method == ['GET']
        buildCacheNodeRequests*.path.every { it.startsWith('node-cache/') }
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.SUCCESS
        buildCacheNodeRequests*.method == ['GET', 'GET']

        where:
        testGradle | testDvPlugin
        GRADLE_6_X | null
        GRADLE_8_X | null
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "keeps remote build cache of connector configured in project when Develocity url is enforced"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin, null, URI.create('https://develocity-server.invalid'))
        declareBuildCacheConnector(testDvPlugin)

        when:
        def config = testConfig().withServer(mockScansServer.address, true)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputEnforcesDevelocityUrl(result, mockScansServer.address.toString(), true)
        outputMissesRemoteBuildCacheConfiguration(result)
        buildCacheNodeRequests*.method == ['GET']
        remoteBuildCacheRequests.empty

        where:
        testGradle | testDvPlugin
        GRADLE_6_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION)
        GRADLE_8_X | dvPlugin(GRADLE_ENTERPRISE, '3.16.2')
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache prior to Gradle 6.0"() {
        when:
        def config = testConfig().withRemoteBuildCache(true, true).withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        outputSkipsRemoteBuildCache(result, "build cache configuration via injection requires Gradle 6.0 or later")
        outputSkipsLocalBuildCacheWithRemoteBuildCache(result)
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config.withDebug(false), buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE
        !result.output.contains("build cache not configured")

        where:
        testGradle << ALL_GRADLE_VERSIONS - BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "warns and does not configure build cache when requested DV plugin version is < 3.11"() {
        when:
        def config = testConfig('3.6.4').withDebug(false).withRemoteBuildCache(true, true)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputSkipsRemoteBuildCache(result, "this version of the Develocity Gradle plugin does not support the Develocity Build Cache connector")
        remoteBuildCacheRequests.empty

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache when project applies DV plugin < 3.11"() {
        given:
        declareDvPluginApplication(testGradle, testDvPlugin)

        when:
        def config = testConfig().withRemoteBuildCache(true, true).withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputSkipsRemoteBuildCache(result, "this version of the Develocity Gradle plugin does not support the Develocity Build Cache connector")
        outputSkipsLocalBuildCacheWithRemoteBuildCache(result)
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config.withDebug(false), buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE
        !result.output.contains("build cache not configured")

        where:
        [testGradle, testDvPlugin] << getVersionsToTestForExistingDvPlugin(BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS)
            .findAll { testGradle, testDvPlugin -> testDvPlugin.pluginId == GRADLE_ENTERPRISE && !testDvPlugin.compatibleWithBuildCacheConnector }
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache without Develocity plugin"() {
        when:
        def config = testConfig().withoutDevelocityPluginVersion().withRemoteBuildCache(true, true).withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputSkipsRemoteBuildCache(result, "the Develocity Gradle plugin is not applied")
        outputSkipsLocalBuildCacheWithRemoteBuildCache(result)
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "does not configure build cache when using configuration cache prior to Gradle 7.5.1"() {
        when:
        def config = testConfig().withDebug(false).withRemoteBuildCache(true, true).withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs('--configuration-cache'))

        then:
        !result.output.contains("build cache not configured")
        remoteBuildCacheRequests.empty

        when:
        // A new configuration cache entry makes the init script run again
        new File(testProjectDir, 'build').deleteDir()
        new File(testProjectDir, '.gradle/configuration-cache').deleteDir()
        result = run(testGradle, config.withDebug(true), buildCacheArgs('--configuration-cache'))

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputMissesLocalBuildCacheConfiguration(result)
        outputSkipsRemoteBuildCache(result, "build cache configuration via injection requires Gradle 7.5.1 or later when using the configuration cache")
        outputSkipsLocalBuildCacheWithRemoteBuildCache(result)
        remoteBuildCacheRequests.empty

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs('--configuration-cache'))

        then:
        result.output.contains("Reusing configuration cache.")
        remoteBuildCacheRequests.empty

        where:
        testGradle << [GRADLE_7_4]
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "remote build cache configuration is configuration cache compatible"() {
        given:
        settingsFile << "buildCache { local { enabled = false } }"

        when:
        def config = testConfig().withRemoteBuildCache(true, true)
        def result = run(testGradle, config, buildCacheArgs('--configuration-cache'))

        then:
        outputContainsRemoteBuildCacheConfiguration(result, 'Develocity server', true, true)
        remoteBuildCacheRequests*.method == ['GET', 'PUT']

        when:
        remoteBuildCacheRequests.clear()
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs('--configuration-cache'))

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        remoteBuildCacheRequests*.method == ['GET']
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE

        where:
        testGradle << CONFIGURATION_CACHE_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can disable local build cache"() {
        when:
        def config = testConfig().withLocalBuildCache(false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputContainsLocalBuildCacheConfiguration(result, false, true)

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.SUCCESS

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "can disable storing in local build cache"() {
        when:
        def config = testConfig().withLocalBuildCache(true, false)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputMissesRemoteBuildCacheConfiguration(result)
        outputContainsLocalBuildCacheConfiguration(result, true, false)

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.SUCCESS

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }

    @Requires({data.testGradle.compatibleWithCurrentJvm})
    def "overrides local build cache configured in project"() {
        given:
        settingsFile << "buildCache { local { enabled = false } }"

        when:
        def config = testConfig().withLocalBuildCache(true)
        def result = run(testGradle, config, buildCacheArgs())

        then:
        outputContainsLocalBuildCacheConfiguration(result, true, true)

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = run(testGradle, config, buildCacheArgs())

        then:
        result.task(':compileJava').outcome == TaskOutcome.FROM_CACHE

        where:
        testGradle << BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS
    }
}
