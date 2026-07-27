#!/usr/bin/env bash
# Release guard: validates that a v<version> tag/release looks like a release.yml-produced
# release. Guards against manual `gh release create` / mis-tagged releases (the v1.4.0
# incident: tag on a pre-feature commit with a 1.3.0 source version, release carrying a
# single asset and no checksums).
#
# Checks (run from the repo root, checked out at the tag):
#   1. build.gradle.kts source version == <version>  (same single source release.yml stamps)
#   2. maven-plugin/pom.xml <version> and <pjacoco.agent.version> == <version>
#      (the 1.2.0 bump missed the agent version once and broke CI with
#       `agent:1.1.0 not found` — see docs/RELEASING.md)
#   3. the GitHub release for the tag carries the full artifact set:
#      required jars (agent + 4 testkit + maven-plugin), a .sha256 per jar, and no
#      jar/checksum orphans. The deprecated jacocoagent-parallel alias is optional
#      (removed after v1.4.x per docs/RELEASING.md) but must have its .sha256 if present.
#
# Deliberately out of scope (consumer-facing literals, not release artifacts): the
# samples/ version literals and README coordinates from the RELEASING.md checklist.
#
# Env:
#   TAG             (required) e.g. v1.4.0
#   GH_REPO         (required for asset check) owner/repo
#   REQUIRE_RELEASE 1 = fail when no release exists for the tag (release/dispatch events);
#                   0 = skip the asset check when there is no release yet (tag push may
#                       race release creation). Default 1.
#   GH_TOKEN        token for gh api (asset check).
set -euo pipefail

fail() { echo "::error::$1"; exit 1; }

TAG="${TAG:?TAG is required (e.g. v1.4.0)}"
REQUIRE_RELEASE="${REQUIRE_RELEASE:-1}"

# Whole-string match ([[ =~ ]] cannot be line-split like grep), so a multi-line or
# otherwise malformed value can never pass validation.
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([._+-][A-Za-z0-9._+-]*)?$ ]]; then
  fail "Tag '$TAG' does not match v<semver>[qualifier]."
fi
VERSION="${TAG#v}"
echo "Validating tag $TAG (version $VERSION)"

# --- 1. Gradle source version (single source of truth, same command release.yml uses) ---
SOURCE_VERSION="$(./gradlew -q --no-daemon properties | awk -F': ' '/^version: /{print $2}')"
[ -n "$SOURCE_VERSION" ] || fail "Could not read the project version from build.gradle.kts."
if [ "$SOURCE_VERSION" != "$VERSION" ]; then
  fail "Tag $TAG points at a commit whose build.gradle.kts version is '$SOURCE_VERSION' (expected '$VERSION'). Tag the version-bump commit instead (docs/RELEASING.md)."
fi
echo "OK: build.gradle.kts version = $VERSION"

# --- 2. maven-plugin pom: its own version AND the agent version it resolves ---
mvn_eval() {
  mvn -B -ntp -q -f maven-plugin/pom.xml help:evaluate "-Dexpression=$1" -DforceStdout
}
POM_VERSION="$(mvn_eval project.version)"
[ -n "$POM_VERSION" ] || fail "Could not read the version from maven-plugin/pom.xml (mvn help:evaluate produced no output)."
if [ "$POM_VERSION" != "$VERSION" ]; then
  fail "Tag $TAG points at a commit whose maven-plugin/pom.xml version is '$POM_VERSION' (expected '$VERSION'). Bump it with the release commit (docs/RELEASING.md checklist)."
fi
POM_AGENT_VERSION="$(mvn_eval pjacoco.agent.version)"
[ -n "$POM_AGENT_VERSION" ] || fail "Could not read pjacoco.agent.version from maven-plugin/pom.xml."
if [ "$POM_AGENT_VERSION" != "$VERSION" ]; then
  fail "maven-plugin/pom.xml pjacoco.agent.version is '$POM_AGENT_VERSION' (expected '$VERSION') — the plugin would resolve the wrong agent (the 1.2.0 incident; docs/RELEASING.md checklist)."
fi
echo "OK: maven-plugin/pom.xml version + pjacoco.agent.version = $VERSION"

# --- 3. Release asset set ---
: "${GH_REPO:?GH_REPO is required (owner/repo)}"
set +e
API_OUT="$(gh api "repos/$GH_REPO/releases/tags/$TAG" --jq '.assets[].name' 2>&1)"
API_STATUS=$?
set -e
if [ "$API_STATUS" -ne 0 ]; then
  if printf '%s' "$API_OUT" | grep -q 'HTTP 404'; then
    # Draft releases are not reachable via the tags endpoint either.
    if [ "$REQUIRE_RELEASE" = "1" ]; then
      fail "No published GitHub release found for tag $TAG (a draft release also 404s here — publish it, then re-validate)."
    fi
    echo "NOTE: no release for $TAG yet - skipping the asset check (a release event will re-run it)."
    exit 0
  fi
  fail "Could not query the release for $TAG (gh api failed, not a 404): $API_OUT"
fi
ASSETS="$API_OUT"

REQUIRED_JARS=(
  "pjacoco-agent-${VERSION}.jar"
  "pjacoco-testkit-${VERSION}.jar"
  "pjacoco-testkit-junit5-${VERSION}.jar"
  "pjacoco-testkit-junit4-${VERSION}.jar"
  "pjacoco-testkit-restassured-${VERSION}.jar"
  "pjacoco-maven-plugin-${VERSION}.jar"
)

has_asset() { printf '%s\n' "$ASSETS" | grep -qxF "$1"; }

MISSING=()
for jar in "${REQUIRED_JARS[@]}"; do
  has_asset "$jar"        || MISSING+=("$jar")
  has_asset "$jar.sha256" || MISSING+=("$jar.sha256")
done

# Deprecated alias: optional, but never a bare jar without its checksum (and vice versa).
ALIAS_JAR="jacocoagent-parallel-${VERSION}.jar"
if has_asset "$ALIAS_JAR" && ! has_asset "$ALIAS_JAR.sha256"; then MISSING+=("$ALIAS_JAR.sha256"); fi
if has_asset "$ALIAS_JAR.sha256" && ! has_asset "$ALIAS_JAR"; then MISSING+=("$ALIAS_JAR"); fi

if [ "${#MISSING[@]}" -gt 0 ]; then
  fail "Release $TAG is missing required assets: ${MISSING[*]}. Releases must be produced by the release.yml workflow, not manual 'gh release create' (docs/RELEASING.md)."
fi

# No stray assets: every asset must be a known jar or its checksum.
KNOWN=("${REQUIRED_JARS[@]}" "$ALIAS_JAR")
while IFS= read -r asset; do
  base="${asset%.sha256}"
  ok=0
  for jar in "${KNOWN[@]}"; do
    [ "$base" = "$jar" ] && ok=1 && break
  done
  [ "$ok" = "1" ] || fail "Release $TAG carries an unexpected asset '$asset' (expected only the release.yml jar set + .sha256 files). If a new asset type is now legitimate, add it to KNOWN in .github/scripts/release-guard.sh."
done <<< "$ASSETS"

echo "OK: release $TAG carries the full asset set ($(printf '%s\n' "$ASSETS" | wc -l | tr -d ' ') assets)."
