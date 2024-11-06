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

abstract class BaseInitScriptTest extends Specification {
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

    // Gradle + plugin versions to test DV injection: used to test with project with no DV plugin defined
    static def getVersionsToTestForPluginInjection(List<TestGradleVersion> gradleVersions = ALL_GRADLE_VERSIONS) {
        [
            gradleVersions,
            [
                "3.6.4", // Support server back to GE 2021.1
                "3.16.2", // Last version before switch to Develocity
                "3.17", // First Develocity plugin
                DEVELOCITY_PLUGIN_VERSION // Latest Develocity plugin
            ]
        ].combinations()
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
                def scanUrlString = "${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID"
                def body = [
                    id     : PUBLIC_BUILD_SCAN_ID,
                    scanUrl: scanUrlString.toString(),
                ]
                def out = new ByteArrayOutputStream()
                new GZIPOutputStream(out).withStream { smileWriter.writeValue(it, body) }
                context.response
                    .contentType('application/vnd.gradle.scan-ack')
                    .send(out.toByteArray())
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
                            .send()
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

    def declareDevelocityPluginApplication(GradleVersion gradleVersion, URI serverUrl = mockScansServer.address) {
        settingsFile.text = maybeAddPluginsToSettings(gradleVersion, false, null, serverUrl) + settingsFile.text
        buildFile.text = maybeAddPluginsToRootProject(gradleVersion, false, null, serverUrl) + buildFile.text
    }

    def declareLegacyGradleEnterprisePluginApplication(GradleVersion gradleVersion, URI serverUrl = mockScansServer.address) {
        settingsFile.text = maybeAddPluginsToSettings(gradleVersion, true, null, serverUrl) + settingsFile.text
        buildFile.text = maybeAddPluginsToRootProject(gradleVersion, true, null, serverUrl) + buildFile.text
    }

    def declareDevelocityPluginAndCcudPluginApplication(GradleVersion gradleVersion, URI serverUrl = mockScansServer.address) {
        settingsFile.text = maybeAddPluginsToSettings(gradleVersion, false, CCUD_PLUGIN_VERSION, serverUrl) + settingsFile.text
        buildFile.text = maybeAddPluginsToRootProject(gradleVersion, false, CCUD_PLUGIN_VERSION, serverUrl) + buildFile.text
    }

    String maybeAddPluginsToSettings(GradleVersion gradleVersion, boolean legacy, String ccudPluginVersion, URI serverUri) {
        if (gradleVersion < GradleVersion.version('6.0')) {
            '' // applied in build.gradle
        } else {
            configuredPlugin(gradleVersion, legacy, ccudPluginVersion, serverUri)
        }
    }

    String maybeAddPluginsToRootProject(GradleVersion gradleVersion, boolean legacy, String ccudPluginVersion, URI serverUrl) {
        if (gradleVersion < GradleVersion.version('5.0')) {
            """
              plugins {
                id 'com.gradle.build-scan' version '1.16'
                ${ccudPluginVersion ? "id 'com.gradle.common-custom-user-data-gradle-plugin' version '$ccudPluginVersion'" : ""}
              }
              buildScan {
                server = '$serverUrl'
                publishAlways()
              }
            """
        } else if (gradleVersion < GradleVersion.version('6.0')) {
            configuredPlugin(gradleVersion, legacy, ccudPluginVersion, serverUrl)
        } else {
            '' // applied in settings.gradle
        }
    }

    String configuredPlugin(GradleVersion gradleVersion, boolean legacy, String ccudPluginVersion, URI serverUri) {
        def pluginId = legacy
            ? (gradleVersion < GradleVersion.version('6.0') ? 'com.gradle.build-scan' : 'com.gradle.enterprise')
            : 'com.gradle.develocity'
        def pluginVersion = legacy ? "3.16.2" : DEVELOCITY_PLUGIN_VERSION
        def configBlock = legacy ? 'gradleEnterprise' : 'develocity'
        """
              plugins {
                id '$pluginId' version '$pluginVersion'
                ${ccudPluginVersion ? "id 'com.gradle.common-custom-user-data-gradle-plugin' version '$ccudPluginVersion'" : ""}
              }
              $configBlock {
                 server = '$serverUri'
                 ${legacy ? "buildScan { publishAlways() }" : ""}
              }
            """
    }

    BuildResult run(List<String> args, GradleVersion gradleVersion = GradleVersion.current(), Map<String, String> envVars = [:]) {
        def result = createRunner(args, gradleVersion, envVars).build()
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
}
