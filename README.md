# Develocity CI auto-injection

This repository is the home for tooling and scripts that allow auto-injection of Develocity into various Build Tool configurations by CI plugins.
It is designed to host the common build-tool integrations that will be leveraged by the various CI plugin implementations.

At this stage, only the Gradle init-script for Develocity has been migrated to this repository.

## Develocity injection Gradle init-script

An init-script that can be used by CI integrations to inject Develocity into a Gradle build.

- The latest source for the init-script can be [found here](https://github.com/gradle/develocity-ci-injection/blob/main/src/main/resources/develocity-injection.init.gradle).
- The repository includes a [set of integration tests](https://github.com/gradle/develocity-ci-injection/blob/main/src/test/groovy/com/gradle/TestDevelocityInjection.groovy) for different features of the init-script.
- The `reference` directory contains the [latest _released_ version of the init-script](https://github.com/gradle/develocity-ci-injection/blob/main/reference/develocity-injection.init.gradle): this script has a version number embedded, and is designed to be re-used in other repositories.
- When executed manually, the [gradle-release.yml workflow](https://github.com/gradle/develocity-ci-injection/actions/workflows/gradle-release.yml) will:
  - Copy the latest script source into `reference`, applying the supplied version number. The version number should be formatted `vX.X[.x]`.
  - Tag the repository with the version number
  - Commit the new reference script to this repository
  - [Create PRs to update the script](https://github.com/gradle/develocity-ci-injection/actions/runs/9102707566/workflow#L48-L57) in various CI plugin repositories. [See here for an example run](https://github.com/gradle/develocity-ci-injection/actions/runs/9102707566) with links to generated PRs.

## Develocity injection input parameters

A number of input parameters can be used to control Develocity injection.

These inputs can be provided via System Properties (eg `-Ddevelocity-injection.url=https://ge.gradle.org`)
or via Environment variables in all caps (eg `DEVELOCITY_INJECTION_URL=https://ge.gradle.org`)

### Control parameters

| Input                                                                          | Required           | Definition                                                     |
|--------------------------------------------------------------------------------|--------------------|----------------------------------------------------------------|
| develocity-injection.init-script-name<br>DEVELOCITY_INJECTION_INIT_SCRIPT_NAME | :white_check_mark: | must match the name of the init-script                         |
| develocity-injection.enabled<br>DEVELOCITY_INJECTION_ENABLED                   | :white_check_mark: | set to 'true' to enable Develocity injection                   |
| develocity-injection.debug<br>DEVELOCITY_INJECTION_DEBUG                       |                    | set to 'true' to enable debug logging for Develocity injection |

### Develocity plugin resolution

| Input                                                                                | Required           | Definition                                                                                                                                   |
|--------------------------------------------------------------------------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| develocity-injection.plugin-version<br>DEVELOCITY_INJECTION_PLUGIN_VERSION           | :white_check_mark: | the version of the [Develocity Gradle plugin](https://docs.gradle.com/develocity/gradle-plugin/) to apply                                    |
| develocity-injection.ccud-plugin-version<br>DEVELOCITY_INJECTION_CCUD_PLUGIN_VERSION |                    | the version of the [Common Custom User Data Gradle plugin](https://github.com/gradle/common-custom-user-data-gradle-plugin) to apply, if any |
| gradle.plugin-repository.url<br>GRADLE_PLUGIN_REPOSITORY_URL                         |                    | the URL of the repository to use when resolving the Develocity and CCUD plugins; the Gradle Plugin Portal is used by default                 |
| gradle.plugin-repository.username<br>GRADLE_PLUGIN_REPOSITORY_USERNAME               |                    | the username for the repository URL to use when resolving the Develocity and CCUD plugins                                                    |
| gradle.plugin-repository.password<br>GRADLE_PLUGIN_REPOSITORY_PASSWORD               |                    | the password for the repository URL to use when resolving the Develocity and CCUD plugins                                                    |

### Develocity configuration

| Input                                                                                            | Required           | Definition                                                                                                                 |
|--------------------------------------------------------------------------------------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------|
| develocity-injection.url<br>DEVELOCITY_INJECTION_URL                                             | :white_check_mark: | the URL of the Develocity server                                                                                           |
| develocity-injection.enforce-url<br>DEVELOCITY_INJECTION_ENFORCE_URL                             |                    | enforce the configured Develocity URL over a URL configured in the project's build                                         |
| develocity-injection.allow-untrusted-server<br>DEVELOCITY_INJECTION_ALLOW_UNTRUSTED_SERVER       |                    | allow communication with an untrusted server; set to _true_ if your Develocity instance is using a self-signed certificate |
| develocity-injection.capture-file-fingerprints<br>DEVELOCITY_INJECTION_CAPTURE_FILE_FINGERPRINTS |                    | enables capturing the paths and content hashes of each individual input file                                               |
| develocity-injection.upload-in-background<br>DEVELOCITY_INJECTION_UPLOAD_IN_BACKGROUND           |                    | set to 'false' to disable background upload of build scans                                                                 |
| develocity-injection.custom-value<br>DEVELOCITY_INJECTION_CUSTOM_VALUE                           |                    | Add a Build Scan custom value to identify auto-injection builds                                                            |
| develocity-injection.terms-of-use.url<br>DEVELOCITY_INJECTION_TERMS_OF_USE_URL                   |                    | enable publishing to scans.gradle.com                                                                                      |
| develocity-injection.terms-of-use.agree<br>DEVELOCITY_INJECTION_TERMS_OF_USE_AGREE               |                    | enable publishing to scans.gradle.com                                                                                      |

## Existing Develocity CI integrations

The following Develocity CI integrations leverage the Gradle init-script from this repository.

- GitHub Actions: [The 'setup-gradle' action](https://github.com/gradle/actions/tree/main/setup-gradle)
- Bamboo: [Develocity Bamboo Plugin](https://github.com/gradle/develocity-bamboo-plugin)
- Jenkins: [Jenkins Gradle Plugin](https://github.com/jenkinsci/gradle-plugin)
- GitLab: [Develocity Gitlab Templates](https://github.com/gradle/develocity-gitlab-templates)
- TeamCity: [TeamCity Build Scan Plugin](https://github.com/etiennestuder/teamcity-build-scan-plugin)

The following Develocity tooling also leverages this init-script.

- [Develocity Build Validation Scripts](https://github.com/gradle/develocity-build-validation-scripts)
