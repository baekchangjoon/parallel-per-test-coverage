#!/usr/bin/env bash
# Install ALL pjacoco artifacts to the local Maven repo (~/.m2) in one step, so consumers can resolve
# every coordinate (agent, testkit ×4, BOTH plugins) without the public repos.
#
# Why one script: the Gradle modules install via `publishToMavenLocal` while the Maven plugin installs via
# `mvn install`. Doing them separately makes it easy to install only half — e.g. agent/testkit/gradle-plugin
# updated but the maven-plugin left at an old version, so `io.pjacoco:pjacoco-maven-plugin:<new>` fails to
# resolve (feedback P4-B). This script keeps them in lockstep.
#
# Usage: ./scripts/install-local.sh            # uses the version from build.gradle.kts
#        ./scripts/install-local.sh 1.4.0      # override the version
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION_ARG=()
if [ "${1:-}" != "" ]; then
  VERSION_ARG=("-PreleaseVersion=$1")
  echo "==> Installing pjacoco $1 to mavenLocal"
else
  echo "==> Installing pjacoco (version from build.gradle.kts) to mavenLocal"
fi

# 1) Gradle modules: agent (shaded) + the four testkit jars + the Gradle plugin.
./gradlew --no-daemon ${VERSION_ARG[@]+"${VERSION_ARG[@]}"} \
  :agent:publishToMavenLocal \
  :testkit-core:publishToMavenLocal :testkit-junit5:publishToMavenLocal \
  :testkit-junit4:publishToMavenLocal :testkit-restassured:publishToMavenLocal \
  :gradle-plugin:publishToMavenLocal

# 2) Maven plugin (resolves io.pjacoco:pjacoco-agent from step 1). Pass the same version through so the
#    plugin pins the agent version it was built against.
MVN_AGENT_ARG=()
if [ "${1:-}" != "" ]; then
  MVN_AGENT_ARG=("-Dpjacoco.agent.version=$1")
fi
mvn -B -ntp -f maven-plugin/pom.xml -DskipTests ${MVN_AGENT_ARG[@]+"${MVN_AGENT_ARG[@]}"} install

echo "==> Done. Installed io.pjacoco artifacts to ~/.m2:"
echo "    pjacoco-agent, pjacoco-testkit[-junit5|-junit4|-restassured], io.pjacoco.gradle (plugin), pjacoco-maven-plugin"
