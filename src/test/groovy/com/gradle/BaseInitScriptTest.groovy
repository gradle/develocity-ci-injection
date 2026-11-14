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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors
import java.util.zip.GZIPOutputStream

import static com.gradle.BaseInitScriptTest.DvPluginId.*

abstract class BaseInitScriptTest extends Specification {
    static final GradleVersion GRADLE_5 = GradleVersion.version('5.0')
    static final GradleVersion GRADLE_6 = GradleVersion.version('6.0')

    static final String DEVELOCITY_PLUGIN_VERSION = '4.3.1'
    static final String CCUD_PLUGIN_VERSION = '2.8.0'

    static final TestGradleVersion GRADLE_3_X = new TestGradleVersion(GradleVersion.version('3.5.1'), 7, 9)
    static final TestGradleVersion GRADLE_4_X = new TestGradleVersion(GradleVersion.version('4.10.3'), 7, 10)
    static final TestGradleVersion GRADLE_5_X = new TestGradleVersion(GradleVersion.version('5.6.4'), 8, 12)
    static final TestGradleVersion GRADLE_6_X = new TestGradleVersion(GradleVersion.version('6.9.4'), 8, 15)
    static final TestGradleVersion GRADLE_7_4 = new TestGradleVersion(GradleVersion.version('7.4.2'), 8, 17)
    static final TestGradleVersion GRADLE_7_X = new TestGradleVersion(GradleVersion.version('7.6.4'), 8, 19)
    static final TestGradleVersion GRADLE_8_0 = new TestGradleVersion(GradleVersion.version('8.0.2'), 8, 19)
    static final TestGradleVersion GRADLE_8_X = new TestGradleVersion(GradleVersion.version('8.13'), 8, 21)

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

    static final List<TestGradleVersion> BUILD_CACHE_CONNECTOR_GRADLE_VERSIONS =
        ALL_GRADLE_VERSIONS.findAll { it.gradleVersion >= GRADLE_6 }

    static final List<TestDvPluginVersion> DV_PLUGIN_VERSIONS = [
        dvPlugin(DEVELOCITY, DEVELOCITY_PLUGIN_VERSION),
        dvPlugin(DEVELOCITY, '3.17'),

        dvPlugin(GRADLE_ENTERPRISE, "3.19.2", true),
        dvPlugin(GRADLE_ENTERPRISE, '3.17', true),
        dvPlugin(GRADLE_ENTERPRISE, '3.16.2'),  // Last version before DV
        dvPlugin(GRADLE_ENTERPRISE, '3.11.1'), // Oldest version compatible with CCUD 2.0.2
        dvPlugin(GRADLE_ENTERPRISE, '3.3.4'), // Introduced background build-scan upload
        dvPlugin(GRADLE_ENTERPRISE, '3.2.1'), // Introduced 'gradleEnterprise.server' element
        dvPlugin(GRADLE_ENTERPRISE, '3.0'), // Earliest version of `com.gradle.enterprise` plugin

        dvPlugin(BUILD_SCAN, "3.19.2", true),
        dvPlugin(BUILD_SCAN, '3.17', true),
        dvPlugin(BUILD_SCAN, '3.16.2'), // Last version before DV
        dvPlugin(BUILD_SCAN, '3.3.4'), // Has background build-scan upload
        dvPlugin(BUILD_SCAN, '3.0'),
        dvPlugin(BUILD_SCAN, '2.4.2'),
        dvPlugin(BUILD_SCAN, '2.0.2'),
        dvPlugin(BUILD_SCAN, '1.16'),
        dvPlugin(BUILD_SCAN, '1.10'),
    ]
    static final BUILD_SCAN_MESSAGES = ["Publishing build scan...", "Publishing Build Scan...", "Publishing Build Scan to Develocity..."]

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
    }

    static final String PUBLIC_BUILD_SCAN_ID = 'i2wepy2gr7ovw'
    static final String DEFAULT_SCAN_UPLOAD_TOKEN = 'scan-upload-token'
    static final String ROOT_PROJECT_NAME = 'test-init-script'
    static final String INIT_SCRIPT_SOURCE = 'src/main/resources/develocity-injection.init.gradle'
    boolean failScanUpload = false

    List<Map<String, String>> remoteBuildCacheRequests = new CopyOnWriteArrayList<>()
    List<Map<String, String>> buildCacheNodeRequests = new CopyOnWriteArrayList<>()

    File settingsFile
    File buildFile
    File gradleProperties
    File initScriptFile

    boolean allowDevelocityDeprecationWarning = false
    boolean usingSystemProperties = false

    @TempDir
    File testProjectDir

    @AutoCleanup
    def mockScansServer = GroovyEmbeddedApp.of {
        def jsonWriter = new ObjectMapper(new JsonFactory()).writer()
        def smileWriter = new ObjectMapper(new SmileFactory()).writer()
        def remoteBuildCacheEntries = new ConcurrentHashMap<String, byte[]>()

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
            prefix('cache') {
                all {
                    remoteBuildCacheRequests << [method: request.method.name, path: request.path, authorization: request.headers.get('Authorization')]
                    if (request.method.name == 'PUT') {
                        context.request.getBody(1024 * 1024 * 10).then { body ->
                            remoteBuildCacheEntries[request.path] = body.bytes
                            context.response.status(200).send()
                        }
                    } else if (remoteBuildCacheEntries.containsKey(request.path)) {
                        context.response.send(remoteBuildCacheEntries[request.path])
                    } else {
                        context.response.status(404).send()
                    }
                }
            }
        }
    }

    // No test loads from the Build Cache Node, so it accepts uploads without storing them
    @AutoCleanup
    def mockBuildCacheNode = GroovyEmbeddedApp.of {
        handlers {
            all {
                buildCacheNodeRequests << [method: request.method.name, path: request.path]
                if (request.method.name == 'PUT') {
                    context.request.getBody(1024 * 1024 * 10).then {
                        context.response.status(200).send()
                    }
                } else {
                    context.response.status(404).send()
                }
            }
        }
    }

    def setup() {
        initScriptFile = new File(testProjectDir, 'develocity-injection.init.gradle')
        initScriptFile.text = new File(INIT_SCRIPT_SOURCE).text

        settingsFile = new File(testProjectDir, 'settings.gradle')
        buildFile = new File(testProjectDir, 'build.gradle')
        gradleProperties = new File(testProjectDir, 'gradle.properties')

        settingsFile << "rootProject.name = '${ROOT_PROJECT_NAME}'\n"
        buildFile << ''
        gradleProperties << ''
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

    void declareDvPluginApplicationWithoutServer(TestDvPluginVersion dvPlugin) {
        settingsFile.text = "plugins { id '${dvPlugin.id}' version '${dvPlugin.version}' }\n"
    }

    void declareJavaProject() {
        buildFile.text = "apply plugin: 'java'\n"
        def sourceFile = new File(testProjectDir, 'src/main/java/Foo.java')
        sourceFile.parentFile.mkdirs()
        // A unique class keeps outputs from being loaded from the local build cache shared between tests
        sourceFile.text = "public class Foo { String id = \"${UUID.randomUUID()}\"; }"
    }

    // Declares a Develocity Build Cache connector that points at the mock Build Cache Node
    void declareBuildCacheConnector(TestDvPluginVersion dvPlugin, String extension = dvPlugin.pluginId == DEVELOCITY ? 'develocity' : 'gradleEnterprise') {
        settingsFile << """
            buildCache {
                remote(${extension}.buildCache) {
                    server = '${buildCacheNodeServer()}'
                    path = 'node-cache'
                    allowInsecureProtocol = true
                    push = false
                }
            }
        """
    }

    void declareHttpBuildCache() {
        settingsFile << """
            buildCache {
                remote(HttpBuildCache) {
                    url = '${mockBuildCacheNode.address}node-cache/'
                    allowInsecureProtocol = true
                }
            }
        """
    }

    String develocityServer() {
        return serverOf(mockScansServer.address)
    }

    String buildCacheNodeServer() {
        return serverOf(mockBuildCacheNode.address)
    }

    private static String serverOf(URI address) {
        return address.toString().replaceAll('/$', '')
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
        println(envVars)
        def result = createRunner(args, testGradle.gradleVersion, envVars).build()
        assertNoDeprecationWarning(result)
        assertNoStackTraces(result)
    }

    BuildResult run(TestGradleVersion testGradle, DvInjectionTestConfig config, List<String> args = ["help"]) {
        return run(args, testGradle, config.envVars)
    }

    static List<String> buildCacheArgs(String... extraArgs) {
        return ['compileJava', '--build-cache'] + extraArgs.toList()
    }

    GradleRunner createRunner(List<String> args, GradleVersion gradleVersion = GradleVersion.current(), Map<String, String> envVars = [:]) {
        args << '-I' << initScriptFile.absolutePath

        def runner = ((DefaultGradleRunner) GradleRunner.create())
            .withGradleVersion(gradleVersion.version)
            .withProjectDir(testProjectDir)
            .forwardOutput()

        if (testKitSupportsEnvVars(gradleVersion) && !usingSystemProperties) {
            runner.withArguments(args).withEnvironment(envVars)
        } else {
            runner.withArguments(mapEnvVarsToSystemProps(envVars) + args)
        }

        runner
    }

    DvInjectionTestConfig testConfig(String develocityPluginVersion = DEVELOCITY_PLUGIN_VERSION) {
        new DvInjectionTestConfig(mockScansServer.address, develocityPluginVersion.toString())
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
            DEVELOCITY_INJECTION_ENABLED                                   : "develocity-injection.enabled",
            DEVELOCITY_INJECTION_INIT_SCRIPT_NAME                          : "develocity-injection.init-script-name",
            DEVELOCITY_INJECTION_DEBUG                                     : "develocity-injection.debug",
            DEVELOCITY_INJECTION_CUSTOM_VALUE                              : "develocity-injection.custom-value",
            DEVELOCITY_INJECTION_URL                                       : "develocity-injection.url",
            DEVELOCITY_INJECTION_ALLOW_UNTRUSTED_SERVER                    : "develocity-injection.allow-untrusted-server",
            DEVELOCITY_INJECTION_ENFORCE_URL                               : "develocity-injection.enforce-url",
            DEVELOCITY_INJECTION_DEVELOCITY_PLUGIN_VERSION                 : "develocity-injection.develocity-plugin.version",
            DEVELOCITY_INJECTION_CCUD_PLUGIN_VERSION                       : "develocity-injection.ccud-plugin.version",
            DEVELOCITY_INJECTION_UPLOAD_IN_BACKGROUND                      : "develocity-injection.upload-in-background",
            DEVELOCITY_INJECTION_CAPTURE_FILE_FINGERPRINTS                 : "develocity-injection.capture-file-fingerprints",
            DEVELOCITY_INJECTION_TERMS_OF_USE_URL                          : "develocity-injection.terms-of-use.url",
            DEVELOCITY_INJECTION_TERMS_OF_USE_AGREE                        : "develocity-injection.terms-of-use.agree",
            DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_URL                     : "develocity-injection.plugin-repository.url",
            DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_USERNAME                : "develocity-injection.plugin-repository.username",
            DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_PASSWORD                : "develocity-injection.plugin-repository.password",
            DEVELOCITY_INJECTION_ENFORCE_BUILD_CACHE                       : "develocity-injection.enforce-build-cache",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ENABLED                : "develocity-injection.remote-build-cache.enabled",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_PUSH                   : "develocity-injection.remote-build-cache.push",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_SERVER                 : "develocity-injection.remote-build-cache.server",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_PATH                   : "develocity-injection.remote-build-cache.path",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ALLOW_INSECURE_PROTOCOL: "develocity-injection.remote-build-cache.allow-insecure-protocol",
            DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ALLOW_UNTRUSTED_SERVER : "develocity-injection.remote-build-cache.allow-untrusted-server",
            DEVELOCITY_INJECTION_LOCAL_BUILD_CACHE_ENABLED                 : "develocity-injection.local-build-cache.enabled",
            DEVELOCITY_INJECTION_LOCAL_BUILD_CACHE_PUSH                    : "develocity-injection.local-build-cache.push",
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

    BuildResult assertNoStackTraces(BuildResult result) {
        assert !result.output.contains("Exception:")
        return result
    }

    void outputContainsBuildScanUrl(BuildResult result) {
        def opt = BUILD_SCAN_MESSAGES.stream().filter { result.output.contains(it) }.findFirst()
        assert opt.isPresent()
        def message = opt.get()
        def buildScanUrl = "${mockScansServer.address}s/$PUBLIC_BUILD_SCAN_ID"
        assert result.output.contains(message)
        assert result.output.contains(buildScanUrl)
        assert 1 == result.output.count(message)
        assert 1 == result.output.count(buildScanUrl)
        assert result.output.indexOf(message) < result.output.indexOf(buildScanUrl)
    }

    void outputMissesBuildScanUrl(BuildResult result) {
        def opt = BUILD_SCAN_MESSAGES.stream().filter { result.output.contains(it) }.findFirst()
        assert !opt.isPresent()
    }

    void outputContainsDevelocityPluginApplicationViaInitScript(BuildResult result, GradleVersion gradleVersion, String pluginVersion = DEVELOCITY_PLUGIN_VERSION) {
        def pluginApplicationLogMsgGradle4 = "Applying com.gradle.scan.plugin.BuildScanPlugin with version 1.16 via init script"
        def pluginApplicationLogMsgBuildScanPlugin = "Applying com.gradle.scan.plugin.BuildScanPlugin with version ${pluginVersion} via init script"
        def pluginApplicationLogMsgGEPlugin = "Applying com.gradle.enterprise.gradleplugin.GradleEnterprisePlugin with version ${pluginVersion} via init script"
        def pluginApplicationLogMsgDVPlugin = "Applying com.gradle.develocity.agent.gradle.DevelocityPlugin with version ${pluginVersion} via init script"

        def isGEPluginVersion = GradleVersion.version(pluginVersion) < GradleVersion.version("3.17")

        if (gradleVersion < GRADLE_5) {
            assert result.output.contains(pluginApplicationLogMsgGradle4)
            assert 1 == result.output.count(pluginApplicationLogMsgGradle4)
            assert !result.output.contains(pluginApplicationLogMsgGEPlugin)
            assert !result.output.contains(pluginApplicationLogMsgDVPlugin)
        } else if (gradleVersion < GRADLE_6 && isGEPluginVersion) {
            assert result.output.contains(pluginApplicationLogMsgBuildScanPlugin)
            assert 1 == result.output.count(pluginApplicationLogMsgBuildScanPlugin)
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

    void outputContainsAcceptingGradleTermsOfUse(BuildResult result) {
        def message = "Accepting Gradle Terms of Use: https://gradle.com/help/legal-terms-of-use"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputContainsUploadInBackground(BuildResult result, boolean uploadInBackground) {
        def message = "Setting uploadInBackground: $uploadInBackground"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputMissesUploadInBackground(BuildResult result) {
        def message = "Setting uploadInBackground:"
        assert !result.output.contains(message)
        assert 0 == result.output.count(message)
    }

    void outputContainsRemoteBuildCacheConfiguration(BuildResult result, String server, boolean enabled, boolean push, String path = null, boolean allowInsecureProtocol = true, Boolean allowUntrustedServer = null) {
        def message = "Configuring the remote build cache: $server, path: ${path ?: '<default>'}, enabled: $enabled, push: $push, allowInsecureProtocol: $allowInsecureProtocol, allowUntrustedServer: ${allowUntrustedServer == null ? '<default>' : allowUntrustedServer}"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputMissesRemoteBuildCacheConfiguration(BuildResult result) {
        assert !result.output.contains("Configuring the remote build cache:")
    }

    void outputContainsLocalBuildCacheConfiguration(BuildResult result, boolean enabled, boolean push) {
        def message = "Configuring the local build cache: enabled: $enabled, push: $push"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputMissesLocalBuildCacheConfiguration(BuildResult result) {
        assert !result.output.contains("Configuring the local build cache:")
    }

    void outputSkipsRemoteBuildCache(BuildResult result, String reason) {
        def message = "Remote build cache not configured because $reason"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    void outputSkipsLocalBuildCacheWithRemoteBuildCache(BuildResult result) {
        def message = "Local build cache not configured to avoid leaving the build cache partially configured"
        assert result.output.contains(message)
        assert 1 == result.output.count(message)
    }

    static class DvInjectionTestConfig {
        String initScriptName = "develocity-injection.init.gradle"
        boolean injectionEnabled = true
        boolean debug = true

        String serverUrl
        boolean enforceUrl = false
        String develocityPluginVersion = null
        String ccudPluginVersion = null
        String pluginRepositoryUrl = null
        String pluginRepositoryUsername = null
        String pluginRepositoryPassword = null
        boolean captureFileFingerprints = false
        String termsOfUseUrl = null
        String termsOfUseAgree = null
        boolean uploadInBackground = true // Need to upload in background since our Mock server doesn't cope with foreground upload
        boolean enforceBuildCache = false
        Boolean remoteBuildCacheEnabled = null
        Boolean remoteBuildCachePush = null
        String remoteBuildCacheServer = null
        String remoteBuildCachePath = null
        boolean remoteBuildCacheAllowInsecureProtocol = true // Need to allow insecure protocol since our Mock servers use plain HTTP
        boolean remoteBuildCacheAllowUntrustedServer = false
        Boolean localBuildCacheEnabled = null
        Boolean localBuildCachePush = null

        DvInjectionTestConfig(URI serverAddress, String develocityPluginVersion) {
            this.serverUrl = serverAddress.toString()
            this.develocityPluginVersion = develocityPluginVersion
        }

        DvInjectionTestConfig withInitScriptName(String initScriptName) {
            this.initScriptName = initScriptName
            return this
        }

        DvInjectionTestConfig withInjectionEnabled(boolean injectionEnabled) {
            this.injectionEnabled = injectionEnabled
            return this
        }

        DvInjectionTestConfig withDebug(boolean debug) {
            this.debug = debug
            return this
        }

        DvInjectionTestConfig withoutDevelocityPluginVersion() {
            develocityPluginVersion = null
            return this
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

        DvInjectionTestConfig withAcceptGradleTermsOfUse() {
            this.termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
            this.termsOfUseAgree = "yes"
            return this
        }

        DvInjectionTestConfig withUploadInBackground(boolean uploadInBackground) {
            this.uploadInBackground = uploadInBackground
            return this
        }

        DvInjectionTestConfig withRemoteBuildCache(Boolean enabled, Boolean push = null) {
            this.remoteBuildCacheEnabled = enabled
            this.remoteBuildCachePush = push
            return this
        }

        DvInjectionTestConfig withRemoteBuildCacheLocation(String server, String path = null) {
            this.remoteBuildCacheServer = server
            this.remoteBuildCachePath = path
            return this
        }

        DvInjectionTestConfig withRemoteBuildCacheAllowInsecureProtocol(boolean allowInsecureProtocol) {
            this.remoteBuildCacheAllowInsecureProtocol = allowInsecureProtocol
            return this
        }

        DvInjectionTestConfig withRemoteBuildCacheAllowUntrustedServer() {
            this.remoteBuildCacheAllowUntrustedServer = true
            return this
        }

        DvInjectionTestConfig withLocalBuildCache(Boolean enabled, Boolean push = null) {
            this.localBuildCacheEnabled = enabled
            this.localBuildCachePush = push
            return this
        }

        DvInjectionTestConfig withEnforceBuildCache() {
            this.enforceBuildCache = true
            return this
        }

        Map<String, String> getEnvVars() {
            Map<String, String> envVars = [
                DEVELOCITY_INJECTION_ENABLED                                   : String.valueOf(injectionEnabled),
                DEVELOCITY_INJECTION_DEBUG                                     : String.valueOf(debug),
                DEVELOCITY_INJECTION_URL                                       : serverUrl,
                DEVELOCITY_INJECTION_ALLOW_UNTRUSTED_SERVER                    : "true",
                DEVELOCITY_INJECTION_UPLOAD_IN_BACKGROUND                      : String.valueOf(uploadInBackground),
                DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ALLOW_INSECURE_PROTOCOL: String.valueOf(remoteBuildCacheAllowInsecureProtocol),
                DEVELOCITY_INJECTION_CUSTOM_VALUE                              : 'gradle-actions'
            ]
            if (initScriptName) envVars.put("DEVELOCITY_INJECTION_INIT_SCRIPT_NAME", initScriptName)
            if (enforceUrl) envVars.put("DEVELOCITY_INJECTION_ENFORCE_URL", "true")
            if (develocityPluginVersion != null) envVars.put("DEVELOCITY_INJECTION_DEVELOCITY_PLUGIN_VERSION", develocityPluginVersion)
            if (ccudPluginVersion != null) envVars.put("DEVELOCITY_INJECTION_CCUD_PLUGIN_VERSION", ccudPluginVersion)
            if (captureFileFingerprints) envVars.put("DEVELOCITY_INJECTION_CAPTURE_FILE_FINGERPRINTS", "true")
            if (termsOfUseUrl != null) envVars.put("DEVELOCITY_INJECTION_TERMS_OF_USE_URL", termsOfUseUrl)
            if (termsOfUseAgree != null) envVars.put("DEVELOCITY_INJECTION_TERMS_OF_USE_AGREE", termsOfUseAgree)
            if (pluginRepositoryUrl != null) envVars.put("DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_URL", pluginRepositoryUrl)
            if (pluginRepositoryUsername != null) envVars.put("DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_USERNAME", pluginRepositoryUsername)
            if (pluginRepositoryPassword != null) envVars.put("DEVELOCITY_INJECTION_PLUGIN_REPOSITORY_PASSWORD", pluginRepositoryPassword)
            if (enforceBuildCache) envVars.put("DEVELOCITY_INJECTION_ENFORCE_BUILD_CACHE", "true")
            if (remoteBuildCacheEnabled != null) envVars.put("DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ENABLED", String.valueOf(remoteBuildCacheEnabled))
            if (remoteBuildCachePush != null) envVars.put("DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_PUSH", String.valueOf(remoteBuildCachePush))
            if (remoteBuildCacheServer != null) envVars.put("DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_SERVER", remoteBuildCacheServer)
            if (remoteBuildCachePath != null) envVars.put("DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_PATH", remoteBuildCachePath)
            if (remoteBuildCacheAllowUntrustedServer) envVars.put("DEVELOCITY_INJECTION_REMOTE_BUILD_CACHE_ALLOW_UNTRUSTED_SERVER", "true")
            if (localBuildCacheEnabled != null) envVars.put("DEVELOCITY_INJECTION_LOCAL_BUILD_CACHE_ENABLED", String.valueOf(localBuildCacheEnabled))
            if (localBuildCachePush != null) envVars.put("DEVELOCITY_INJECTION_LOCAL_BUILD_CACHE_PUSH", String.valueOf(localBuildCachePush))
            return envVars
        }
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

        boolean isCompatibleWithUploadInBackground() {
            if (pluginId == BUILD_SCAN || pluginId == GRADLE_ENTERPRISE) {
                // Only BS & GE plugins 3.3.4+ support uploadInBackground
                return pluginVersionAtLeast('3.3.4')
            }
            return true
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

        boolean isCompatibleWithBuildCacheConnector() {
            if (pluginId == GRADLE_ENTERPRISE) {
                return pluginVersionAtLeast('3.11')
            }
            return pluginId == DEVELOCITY
        }

        boolean isCompatibleWithCCUD() {
            if (pluginId == BUILD_SCAN) {
                return version == '1.16'
            }
            return pluginVersionAtLeast('3.2')
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
