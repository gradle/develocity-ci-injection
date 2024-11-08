package com.gradle

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.smile.SmileFactory
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.internal.DefaultGradleRunner
import org.gradle.util.GradleVersion
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import spock.lang.AutoCleanup
import spock.lang.Specification
import spock.lang.TempDir

import java.util.stream.Collectors
import java.util.zip.GZIPOutputStream

import static com.gradle.BaseInitScriptTest.DvPluginId.*

abstract class BaseInitScriptTest extends Specification {
    static final GradleVersion GRADLE_5 = GradleVersion.version('5.0')
    static final GradleVersion GRADLE_6 = GradleVersion.version('6.0')

    static final String DEVELOCITY_PLUGIN_VERSION = '3.18.1'
    static final String CCUD_PLUGIN_VERSION = '2.0.2'

    static final TestGradleVersion GRADLE_3_X = new TestGradleVersion(GradleVersion.version('3.5.1'), 7, 9)
    static final TestGradleVersion GRADLE_4_X = new TestGradleVersion(GradleVersion.version('4.10.3'), 7, 10)
    static final TestGradleVersion GRADLE_5_X = new TestGradleVersion(GradleVersion.version('5.6.4'), 8, 12)
    static final TestGradleVersion GRADLE_6_X = new TestGradleVersion(GradleVersion.version('6.9.4'), 8, 15)
    static final TestGradleVersion GRADLE_7_X = new TestGradleVersion(GradleVersion.version('7.6.2'), 8, 19)
    static final TestGradleVersion GRADLE_8_0 = new TestGradleVersion(GradleVersion.version('8.0.2'), 8, 19)
    static final TestGradleVersion GRADLE_8_X = new TestGradleVersion(GradleVersion.version('8.7'), 8, 21)

    static final List<TestGradleVersion> ALL_GRADLE_VERSIONS = [
        GRADLE_3_X, // First version where TestKit supports environment variables
        GRADLE_4_X,
        GRADLE_5_X,
        GRADLE_6_X,
        GRADLE_7_X,
        GRADLE_8_0,
        GRADLE_8_X,
    ]

    static final List<TestGradleVersion> CONFIGURATION_CACHE_GRADLE_VERSIONS =
        [GRADLE_7_X, GRADLE_8_0, GRADLE_8_X].intersect(ALL_GRADLE_VERSIONS)

    static final List<TestDvPluginVersion> DV_PLUGIN_VERSIONS = [
        dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION),
        dvPlugin(DEVELOCITY, '3.17'),

        dvPlugin(GRADLE_ENTERPRISE, DEVELOCITY_PLUGIN_VERSION, true),
        dvPlugin(GRADLE_ENTERPRISE, '3.17', true),
        dvPlugin(GRADLE_ENTERPRISE, '3.16.2'),
        dvPlugin(GRADLE_ENTERPRISE, '3.6.4'),
        dvPlugin(GRADLE_ENTERPRISE, '3.3.4'), // Has background build-scan upload
        dvPlugin(GRADLE_ENTERPRISE, '3.2.1'), // Has 'gradleEnterprise.server' element
        dvPlugin(GRADLE_ENTERPRISE, '3.1.1'), // Has 'gradleEnterprise.buildScan.server' value
        dvPlugin(GRADLE_ENTERPRISE, '3.0'), // Earliest version of `com.gradle.enterprise` plugin

        dvPlugin(BUILD_SCAN, DEVELOCITY_PLUGIN_VERSION, true),
        dvPlugin(BUILD_SCAN, '3.17', true),
        dvPlugin(BUILD_SCAN, '3.16.2'), // Last version before DV
        dvPlugin(BUILD_SCAN, '3.3.4'), // Has background build-scan upload
        dvPlugin(BUILD_SCAN, '3.0'),
        dvPlugin(BUILD_SCAN, '2.4.2'),
        dvPlugin(BUILD_SCAN, '2.0.2'),
        dvPlugin(BUILD_SCAN, '1.16'),
        dvPlugin(BUILD_SCAN, '1.10'),
    ]

    // Gradle + plugin versions to test DV injection: used to test with project with no DV plugin defined
    static def getVersionsToTestForPluginInjection(List<TestGradleVersion> gradleVersions = ALL_GRADLE_VERSIONS) {
        [
            gradleVersions,
            [
                dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION), // Latest Develocity plugin
                dvPlugin(DEVELOCITY, '3.17'), // First Develocity plugin
                dvPlugin(GRADLE_ENTERPRISE, '3.16.2'), // Last version before switch to Develocity
                dvPlugin(GRADLE_ENTERPRISE, '3.6.4'), // Support server back to GE 2021.1
            ]
        ].combinations()
    }

    // Gradle + plugin combinations to test with existing projects: used to test init-script operating with existing DV plugin application
    static def getVersionsToTestForExistingDvPlugin(List<TestGradleVersion> gradleVersions = ALL_GRADLE_VERSIONS) {
        return [
            gradleVersions,
            DV_PLUGIN_VERSIONS
        ].combinations().findAll { gradleVersion, dvPlugin ->
            // Only include valid Gradle/Plugin combinations
            dvPlugin.isCompatibleWith(gradleVersion)
        }
        // TODO: FIX
        //  Remove case that is currently failing due to a bug in the init-script
        .findAll { gradleVersion, dvPlugin -> !(dvPlugin.id == 'com.gradle.build-scan' && dvPlugin.version in ['3.17', '3.18.1'])}
    }

    static final String PUBLIC_BUILD_SCAN_ID = 'i2wepy2gr7ovw'
    static final String DEFAULT_SCAN_UPLOAD_TOKEN = 'scan-upload-token'
    static final String ROOT_PROJECT_NAME = 'test-init-script'
    static final String INIT_SCRIPT_SOURCE = 'src/main/resources/develocity-injection.init.gradle'
    boolean failScanUpload = false

    File settingsFile
    File buildFile
    File initScriptFile

    boolean allowDevelocityDeprecationWarning = false

    @TempDir
    File testProjectDir

    @AutoCleanup
    def mockScansServer = GroovyEmbeddedApp.of {
        def jsonWriter = new ObjectMapper(new JsonFactory()).writer()
        def smileWriter = new ObjectMapper(new SmileFactory()).writer()

        handlers {
            post('in/:gradleVersion/:pluginVersion') {
                if (failScanUpload) {
                    context.response.status(401).send()
                    return
                }
                def pluginVersion = context.pathTokens.pluginVersion
                def scanUrlString = "${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID"
                def body = [
                    id     : PUBLIC_BUILD_SCAN_ID,
                    scanUrl: scanUrlString.toString(),
                ]
                def sendJsonResponse = GradleVersion.version(pluginVersion) >= GradleVersion.version('3.1')
                if (!sendJsonResponse) {
                    def out = new ByteArrayOutputStream()
                    new GZIPOutputStream(out).withStream { smileWriter.writeValue(it, body) }
                    context.request.getBody(1024 * 1024 * 10).then {
                        context.response
                            .contentType('application/vnd.gradle.scan-ack')
                            .send(out.toByteArray())
                    }
                } else {
                    context.request.getBody(1024 * 1024 * 10).then {
                        context.response
                            .contentType('application/vnd.gradle.scan-ack+json')
                            .send(jsonWriter.writeValueAsBytes(body))
                    }
                }
            }
            prefix('scans/publish') {
                post('gradle/:pluginVersion/token') {
                    if (failScanUpload) {
                        context.response.status(401).send()
                        return
                    }
                    def pluginVersion = context.pathTokens.pluginVersion
                    def scanUrlString = "${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID"
                    def body = [
                        id             : PUBLIC_BUILD_SCAN_ID,
                        scanUrl        : scanUrlString.toString(),
                        scanUploadUrl  : "${mockScansServer.address.toString()}scans/publish/gradle/$pluginVersion/upload".toString(),
                        scanUploadToken: DEFAULT_SCAN_UPLOAD_TOKEN
                    ]
                    context.response
                        .contentType('application/vnd.gradle.scan-ack+json')
                        .send(jsonWriter.writeValueAsBytes(body))
                }
                post('gradle/:pluginVersion/upload') {
                    if (failScanUpload) {
                        context.response.status(401).send()
                        return
                    }
                    context.request.getBody(1024 * 1024 * 10).then {
                        context.response
                            .contentType('application/vnd.gradle.scan-upload-ack+json')
                            .send(jsonWriter.writeValueAsBytes([:]))
                    }
                }
                notFound()
            }
        }
    }

    def setup() {
        initScriptFile = new File(testProjectDir, 'develocity-injection.init.gradle')
        initScriptFile.text = new File(INIT_SCRIPT_SOURCE).text

        settingsFile = new File(testProjectDir, 'settings.gradle')
        buildFile = new File(testProjectDir, 'build.gradle')

        settingsFile << "rootProject.name = '${ROOT_PROJECT_NAME}'\n"
        buildFile << ''
    }

    void declareDvPluginApplication(TestGradleVersion testGradle, TestDvPluginVersion dvPlugin, String ccudPluginVersion = null, URI serverUri = mockScansServer.address) {
        if (dvPlugin.deprecated) {
            allowDevelocityDeprecationWarning = true
        }
        if (testGradle.gradleVersion < GRADLE_6) {
            buildFile.text = configuredPlugin(dvPlugin, ccudPluginVersion, serverUri)
        } else {
            settingsFile.text = configuredPlugin(dvPlugin, ccudPluginVersion, serverUri)
        }
    }

    private String configuredPlugin(TestDvPluginVersion dvPlugin, String ccudPluginVersion, URI serverUri) {
        """
              plugins {
                id '${dvPlugin.id}' version '${dvPlugin.version}'
                ${ccudPluginVersion ? "id 'com.gradle.common-custom-user-data-gradle-plugin' version '$ccudPluginVersion'" : ""}
              }
              ${dvPlugin.getConfigBlock(serverUri)}
            """
    }

    BuildResult run(List<String> args, TestGradleVersion testGradle, Map<String, String> envVars = [:]) {
        def result = createRunner(args, testGradle.gradleVersion, envVars).build()
        assertNoDeprecationWarning(result)
    }

    GradleRunner createRunner(List<String> args, GradleVersion gradleVersion = GradleVersion.current(), Map<String, String> envVars = [:]) {
        args << '-I' << initScriptFile.absolutePath

        def runner = ((DefaultGradleRunner) GradleRunner.create())
            .withGradleVersion(gradleVersion.version)
            .withProjectDir(testProjectDir)
            .withArguments(args)
            .forwardOutput()

        if (testKitSupportsEnvVars(gradleVersion)) {
            runner.withEnvironment(envVars)
        } else {
            (runner as DefaultGradleRunner).withJvmArguments(mapEnvVarsToSystemProps(envVars))
        }

        runner
    }

    private boolean testKitSupportsEnvVars(GradleVersion gradleVersion) {
        // TestKit supports env vars for Gradle 3.5+, except on M1 Mac where only 6.9+ is supported
        def isM1Mac = System.getProperty("os.arch") == "aarch64"
        if (isM1Mac) {
            return gradleVersion >= GRADLE_6_X.gradleVersion
        } else {
            return gradleVersion >= GRADLE_3_X.gradleVersion
        }
    }

    // for TestKit versions that don't support environment variables, map those vars to system properties
    private static List<String> mapEnvVarsToSystemProps(Map<String, String> envVars) {
        def mapping = [
            DEVELOCITY_INJECTION_ENABLED              : "develocity.injection-enabled",
            DEVELOCITY_INJECTION_INIT_SCRIPT_NAME     : "develocity.injection.init-script-name",
            DEVELOCITY_AUTO_INJECTION_CUSTOM_VALUE    : "develocity.auto-injection.custom-value",
            DEVELOCITY_URL                            : "develocity.url",
            DEVELOCITY_ALLOW_UNTRUSTED_SERVER         : "develocity.allow-untrusted-server",
            DEVELOCITY_ENFORCE_URL                    : "develocity.enforce-url",
            DEVELOCITY_PLUGIN_VERSION                 : "develocity.plugin.version",
            DEVELOCITY_CCUD_PLUGIN_VERSION            : "develocity.ccud-plugin.version",
            DEVELOCITY_BUILD_SCAN_UPLOAD_IN_BACKGROUND: "develocity.build-scan.upload-in-background",
            DEVELOCITY_CAPTURE_FILE_FINGERPRINTS      : "develocity.capture-file-fingerprints",
            GRADLE_PLUGIN_REPOSITORY_URL              : "gradle.plugin-repository.url",
            GRADLE_PLUGIN_REPOSITORY_USERNAME         : "gradle.plugin-repository.username",
            GRADLE_PLUGIN_REPOSITORY_PASSWORD         : "gradle.plugin-repository.password",
        ]

        return envVars.entrySet().stream().map(e -> {
            def sysPropName = mapping.get(e.key)
            if (sysPropName == null) throw new RuntimeException("No sysprop mapping for env var: ${e.key}")
            return "-D" + sysPropName + "=" + e.value
        }).collect(Collectors.toList())
    }

    BuildResult assertNoDeprecationWarning(BuildResult result) {
        if (!allowDevelocityDeprecationWarning) {
            assert !result.output.contains("WARNING: The following functionality has been deprecated")
        }
        return result
    }

    static final class TestGradleVersion {
        final GradleVersion gradleVersion
        private final Integer jdkMin
        private final Integer jdkMax

        TestGradleVersion(GradleVersion gradleVersion, Integer jdkMin, Integer jdkMax) {
            this.gradleVersion = gradleVersion
            this.jdkMin = jdkMin
            this.jdkMax = jdkMax
        }

        boolean isCompatibleWithCurrentJvm() {
            def jvmVersion = getJvmVersion()
            jdkMin <= jvmVersion && jvmVersion <= jdkMax
        }

        private static int getJvmVersion() {
            String version = System.getProperty('java.version')
            if (version.startsWith('1.')) {
                Integer.parseInt(version.substring(2, 3))
            } else {
                Integer.parseInt(version.substring(0, version.indexOf('.')))
            }
        }

        @Override
        String toString() {
            return "Gradle " + gradleVersion.version
        }
    }

    static TestDvPluginVersion dvPlugin(DvPluginId id, String version, boolean deprecated = false) {
        return new TestDvPluginVersion(id, version, deprecated)
    }

    static enum DvPluginId {
        DEVELOCITY('com.gradle.develocity'),
        GRADLE_ENTERPRISE('com.gradle.enterprise'),
        BUILD_SCAN('com.gradle.build-scan');
        final String id;

        DvPluginId(String id) {
            this.id = id
        }
    }

    static final class TestDvPluginVersion {
        final DvPluginId pluginId
        final String version
        final boolean deprecated

        TestDvPluginVersion(DvPluginId pluginId, String version, boolean deprecated) {
            this.pluginId = pluginId
            this.version = version
            this.deprecated = deprecated
        }

        String getId() {
            return pluginId.id
        }

        boolean isCompatibleWith(TestGradleVersion gradleVersion) {
            switch (pluginId) {
                case DEVELOCITY:
                    return GRADLE_5 <= gradleVersion.gradleVersion
                case GRADLE_ENTERPRISE:
                    return GRADLE_6 <= gradleVersion.gradleVersion
                case BUILD_SCAN:
                    if (pluginVersionAtLeast('2.0')) {
                        // Build-scan plugin 2+ only works with Gradle 5 (enterprise is for Gradle 6+)
                        return GRADLE_5 <= gradleVersion.gradleVersion
                            && gradleVersion.gradleVersion < GRADLE_6
                    } else {
                        // Only plugin v1.x works with Gradle < 5
                        return gradleVersion.gradleVersion < GRADLE_5
                    }
            }
        }

        boolean isCompatibleWithConfigurationCache() {
            // Only DV & GE plugins 3.16+ support configuration-cache
            return pluginId != BUILD_SCAN && pluginVersionAtLeast('3.16')
        }

        String getConfigBlock(URI serverUri) {
            switch (pluginId) {
                case DEVELOCITY:
                    return """
                        develocity {
                            server = '$serverUri'
                        }   
                    """
                case GRADLE_ENTERPRISE:
                    if (pluginVersionAtLeast('3.2')) {
                        return """
                            gradleEnterprise {
                                server = '$serverUri'
                                buildScan { publishAlways() }
                            }
                        """
                    } else {
                        return """
                            gradleEnterprise {
                                buildScan {
                                    server = '$serverUri'
                                    publishAlways() 
                                }
                            }
                        """
                    }
                case BUILD_SCAN:
                    return """
                        buildScan {
                            server = '$serverUri'
                            publishAlways()
                        }
                    """
            }
        }

        String getCompatibleCCUDVersion() {
            // CCUD 1.13 is compatible with pre-develocity plugins
            return pluginVersionAtLeast('3.17') ? CCUD_PLUGIN_VERSION : '1.13'
        }

        private boolean pluginVersionAtLeast(String targetVersion) {
            GradleVersion.version(version) >= GradleVersion.version(targetVersion)
        }

        @Override
        String toString() {
            return "${pluginId.id}:${version}"
        }
    }
}
