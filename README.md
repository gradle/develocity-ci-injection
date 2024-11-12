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
  - Copy the latest script source into `reference`, applying the supplied version number
  - Tag the repository with the version number
  - Commit the new reference script to this repository
  - [Create PRs to update the script](https://github.com/gradle/develocity-ci-injection/actions/runs/9102707566/workflow#L48-L57) in various CI plugin repositories. [See here for an example run](https://github.com/gradle/develocity-ci-injection/actions/runs/9102707566) with links to generated PRs.
 
## Existing Develocity CI integrations

The following Develocity CI integrations leverage the Gradle init-script from this repository.

- GitHub Actions: [The 'setup-gradle' action](https://github.com/gradle/actions/tree/main/setup-gradle)
- Bamboo: [Develocity Bamboo Plugin](https://github.com/gradle/develocity-bamboo-plugin)
- Jenkins: [Jenkins Gradle Plugin](https://github.com/jenkinsci/gradle-plugin)
- GitLab: [Develocity Gitlab Templates](https://github.com/gradle/develocity-gitlab-templates)
- TeamCity: [TeamCity Build Scan Plugin](https://github.com/etiennestuder/teamcity-build-scan-plugin)

The following Develocity tooling also leverages this init-script.

- [Develocity Build Validation Scripts](https://github.com/gradle/gradle-enterprise-build-validation-scripts)
