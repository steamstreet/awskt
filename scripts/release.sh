#!/usr/bin/env bash
#
# Cuts a release of awskt to Maven Central: validates, uploads, and publishes.
#
# Usage:
#   scripts/release.sh [--scope patch|minor|major] [--yes] [--dry-run] [--skip-check]
#
# The steps, in order:
#   1. preflight   — clean tree, branch in sync with origin, credentials present
#   2. version     — asked of nebula rather than assumed
#   3. check       — a clean `check`, so the release is verified rather than hoped for
#   4. final       — uploads every module and closes its staging repository, tags, pushes
#   5. plugin      — `gradle-plugin` is an `includeBuild` and takes no part in `final`
#   6. validate    — waits for both deployments to reach VALIDATED
#   7. publish     — the irreversible step, confirmed unless --yes
#   8. verify      — waits for PUBLISHED and for the artifacts to answer on repo1
#
# Two things this script exists to prevent, both of which have already happened once:
#
#   `final` exits 0 without publishing anything. It closes the staging repository, and
#   the deployment then sits at VALIDATED until something explicitly publishes it. A
#   release driven by `final` alone looks successful and ships nothing.
#
#   A close that times out fails the build *after* the artifacts are uploaded, leaving
#   the release untagged and the next build reusing the version. The build sets a
#   30 minute clientTimeout for this reason; a close of the full module set measured
#   272 seconds against a default of five minutes.

set -euo pipefail

OSSRH_API="https://ossrh-staging-api.central.sonatype.com"
PORTAL_API="https://central.sonatype.com/api/v1/publisher"
REPO1="https://repo1.maven.org/maven2"

SCOPE=""
ASSUME_YES=0
DRY_RUN=0
SKIP_CHECK=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope)      SCOPE="${2:-}"; shift 2 ;;
    --scope=*)    SCOPE="${1#*=}"; shift ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --skip-check) SKIP_CHECK=1; shift ;;
    -h|--help)    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -n "$SCOPE" && ! "$SCOPE" =~ ^(patch|minor|major)$ ]]; then
  echo "--scope must be patch, minor or major" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

confirm() {
  [[ $ASSUME_YES -eq 1 ]] && return 0
  local reply
  read -r -p "$1 [y/N] " reply </dev/tty
  [[ "$reply" == "y" || "$reply" == "Y" ]]
}

# --- 1. preflight ------------------------------------------------------------

say "Preflight"

[[ -f "$ROOT/gradlew" ]] || die "not in the awskt repository root"

if [[ -n "$(git status --porcelain)" ]]; then
  die "working tree is dirty; nebula will not release from it"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git fetch --quiet origin "$BRANCH" || die "could not fetch origin/$BRANCH"

if [[ -n "$(git rev-list "origin/$BRANCH..HEAD")" ]]; then
  die "$BRANCH has unpushed commits; push them first so the tag refers to a commit on origin"
fi

GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
read_prop() { grep -E "^$1=" "$GRADLE_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true; }

CENTRAL_USER="${MAVEN_CENTRAL_USERNAME:-$(read_prop mavenCentralUsername)}"
CENTRAL_PASS="${MAVEN_CENTRAL_PASSWORD:-$(read_prop mavenCentralPassword)}"

[[ -n "$CENTRAL_USER" && -n "$CENTRAL_PASS" ]] || \
  die "mavenCentralUsername/mavenCentralPassword not found in $GRADLE_PROPS"
[[ -n "$(read_prop signing.keyId)" ]] || \
  die "signing.keyId not found in $GRADLE_PROPS; Central rejects unsigned artifacts"

TOKEN="$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64)"

info "branch:      $BRANCH (in sync with origin)"
info "credentials: present"

api_get()  { curl -fsS -u "$CENTRAL_USER:$CENTRAL_PASS" -H "Accept: application/json" --max-time 120 "$@"; }
api_post() { curl -fsS -X POST -H "Authorization: Bearer $TOKEN" --max-time 300 "$@"; }

# --- 2. version --------------------------------------------------------------

say "Resolving version"

SCOPE_ARG=()
[[ -n "$SCOPE" ]] && SCOPE_ARG=(-Prelease.scope="$SCOPE")

VERSION="$(./gradlew properties -Prelease.stage=final "${SCOPE_ARG[@]}" --console=plain -q 2>/dev/null \
  | grep -E '^version:' | head -1 | awk '{print $2}')"

[[ -n "$VERSION" ]] || die "could not determine the version nebula would use"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "resolved version '$VERSION' is not a release version; check --scope and the branch name"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && \
  die "tag v$VERSION already exists; this version has been released"

info "version: $VERSION"

# --- 3. check ----------------------------------------------------------------

if [[ $SKIP_CHECK -eq 1 ]]; then
  say "Skipping check (--skip-check)"
else
  say "Running clean check"
  info "this takes roughly 25 minutes; every target is compiled and the ABI dumps verified"
  ./gradlew clean check --console=plain || die "check failed; nothing has been uploaded"
fi

if [[ $DRY_RUN -eq 1 ]]; then
  say "Dry run complete"
  info "would have released $VERSION from $BRANCH"
  exit 0
fi

# --- 4/5. upload -------------------------------------------------------------

say "Releasing $VERSION"
if ! confirm "Upload $VERSION to Sonatype and tag v$VERSION?"; then
  die "aborted before upload"
fi

# Staging repositories that already exist are not ours; remember them so the
# deployments this run creates can be identified without guessing.
PRE_EXISTING="$(api_get "$OSSRH_API/manual/search/repositories" \
  | python3 -c 'import json,sys; print(" ".join(r["key"] for r in json.load(sys.stdin).get("repositories",[])))')"
[[ -n "$PRE_EXISTING" ]] && info "note: staging repositories already exist and will be left alone"

./gradlew final "${SCOPE_ARG[@]}" --console=plain || \
  die "the release build failed; check whether artifacts were uploaded and whether v$VERSION was tagged before retrying"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "release finished but tag v$VERSION was not created"
git ls-remote --tags origin "v$VERSION" | grep -q . || die "tag v$VERSION was not pushed to origin"
info "tagged and pushed v$VERSION"

say "Publishing the Gradle plugin at $VERSION"
info 'gradle-plugin is an includeBuild, so the final task does not cover it'
./gradlew --project-dir gradle-plugin -Pawskt.pluginVersion="$VERSION" \
  publishToSonatype closeSonatypeStagingRepository --console=plain || die "the plugin build failed to upload"

# --- 6. validate -------------------------------------------------------------

say "Waiting for both deployments to validate"

DEPLOYMENTS=""
for _ in $(seq 1 30); do
  DEPLOYMENTS="$(api_get "$OSSRH_API/manual/search/repositories" | PRE="$PRE_EXISTING" VER="$VERSION" python3 -c '
import json, os, sys
pre = set(os.environ["PRE"].split())
ver = os.environ["VER"]
out = []
for r in json.load(sys.stdin).get("repositories", []):
    if r["key"] in pre:
        continue
    if not (r.get("description") or "").endswith(":" + ver):
        continue
    if r.get("portal_deployment_id"):
        out.append(r["portal_deployment_id"] + "=" + r["description"])
print(" ".join(out))')"
  [[ "$(wc -w <<<"$DEPLOYMENTS")" -ge 2 ]] && break
  sleep 20
done

[[ "$(wc -w <<<"$DEPLOYMENTS")" -ge 2 ]] || \
  die "expected two deployments for $VERSION, found: ${DEPLOYMENTS:-none}"

# Emits "STATE<TAB>{errors json}" on one line, so this stays bash 3.2 compatible
# (macOS ships 3.2, which has no `mapfile`).
deployment_state() {
  api_post "$PORTAL_API/status?id=$1" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("%s\t%s" % (d.get("deploymentState", "UNKNOWN"), json.dumps(d.get("errors") or {})))'
}

for entry in $DEPLOYMENTS; do
  id="${entry%%=*}"; desc="${entry#*=}"
  state=""
  for _ in $(seq 1 60); do
    line="$(deployment_state "$id")"
    state="${line%%$'\t'*}"; errors="${line#*$'\t'}"
    case "$state" in
      VALIDATED|PUBLISHING|PUBLISHED) info "$desc -> $state"; break ;;
      FAILED) die "$desc FAILED validation: $errors" ;;
      *) sleep 20 ;;
    esac
  done
  case "$state" in
    VALIDATED|PUBLISHING|PUBLISHED) ;;
    *) die "$desc stuck in state $state" ;;
  esac
done

# --- 7. publish --------------------------------------------------------------

say "Publishing to Maven Central"
info "this cannot be undone; Central artifacts are never withdrawn"
for entry in $DEPLOYMENTS; do info "  ${entry#*=}"; done

if ! confirm "Publish $VERSION permanently?"; then
  info "left both deployments at VALIDATED; publish them at https://central.sonatype.com/publishing/deployments"
  exit 1
fi

for entry in $DEPLOYMENTS; do
  id="${entry%%=*}"; desc="${entry#*=}"
  line="$(deployment_state "$id")"; state="${line%%$'\t'*}"
  if [[ "$state" == "VALIDATED" ]]; then
    # Not `&& info ...`: under `set -e` a failing left-hand side of `&&` is exempt, so
    # a rejected publish would be skipped in silence — the failure mode this whole
    # script exists to rule out.
    if api_post "$PORTAL_API/deployment/$id" >/dev/null; then
      info "published $desc"
    else
      die "publish request for $desc was rejected; it is still at VALIDATED"
    fi
  else
    info "skipped $desc (already $state)"
  fi
done

# --- 8. verify ---------------------------------------------------------------

say "Waiting for the artifacts to appear on repo1"
info "propagation usually takes 10-30 minutes"

PROBES=""
for entry in $DEPLOYMENTS; do
  PROBES="$PROBES $(api_post "$PORTAL_API/status?id=${entry%%=*}" | python3 -c '
import json, sys, re
d = json.load(sys.stdin)
seen, out = set(), []
for p in d.get("purls", []):
    m = re.match(r"pkg:maven/([^/]+)/([^@]+)@([^?]+)", p)
    if not m:
        continue
    g, a, v = m.groups()
    if (g, a) in seen:
        continue
    seen.add((g, a))
    out.append("%s/%s/%s" % (g.replace(".", "/"), a, v))
    if len(out) >= 2:
        break
print(" ".join(out))')"
done

missing=0
for _ in $(seq 1 40); do
  missing=0
  for path in $PROBES; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$REPO1/$path/")"
    [[ "$code" == "200" ]] || missing=1
  done
  [[ $missing -eq 0 ]] && break
  sleep 60
done

say "Release complete: $VERSION"
for path in $PROBES; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$REPO1/$path/")"
  info "HTTP $code  $REPO1/$path/"
done
[[ $missing -eq 0 ]] || info "not all artifacts are answering yet; propagation can lag, the deployments are published"
