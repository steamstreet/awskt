#!/usr/bin/env bash
#
# Cuts a release of awskt. By default it publishes to the Steamstreet repository only, which
# answers at https://repo.steamstreet.com within a minute or two of the upload. `--target central`
# also publishes to Maven Central, which takes about two hours to reach repo1, for the occasional
# public release. Either way the Steamstreet repository gets every version.
#
# Usage:
#   scripts/release.sh [--target steamstreet|central] [--check full|jvm|none]
#                      [--scope patch|minor|major] [--yes] [--dry-run]
#   scripts/release.sh --resume [--yes]
#
# `--resume` publishes the version tagged at HEAD to the Steamstreet repository again, without a
# check and without tagging: to finish a release whose tag was pushed before its uploads completed,
# or to backfill an older tag into the repository (check the tag out first). Uploads of a version
# replace the same coordinates, so running it twice is harmless.
#
# `--check` sets how much is verified before anything is uploaded:
#   jvm   the JVM tests only (jvmTest, and test in the JVM-only modules), without a clean. About
#         15 minutes from a cold build, less when the build cache is warm. The default for
#         --target steamstreet.
#   full  a clean `check`: every target compiled and tested, and the ABI dumps verified. About 25
#         minutes. The default for --target central, whose releases are public and permanent.
#   none  nothing. `--skip-check` is the same.
# Whatever the level, `final` still compiles every target to publish it, so a native compile error
# still stops the release; `jvm` gives up the native tests and the ABI-dump check.
#
# Publishing to the Steamstreet repository uses the AWS profile named by AWSKT_PUBLISH_PROFILE,
# `steamstreet-publisher` by default: the access key of the IAM user steamstreet-maven-publisher,
# which can only write the bucket's Maven tree (infrastructure/package-repository.yaml). A static
# key rather than an SSO profile, so that a release never waits on a login.
#
# The steps for `--target steamstreet`: preflight, version, check, upload (every module, then the
# plugin), verify that the POMs answer at repo.steamstreet.com, and only then tag and push. A
# release interrupted before the tag leaves nothing to clean up: run it again and it re-uploads.
# It does not use nebula's `final` task, which pushes the tag before the uploads finish; that left
# 3.1.6 tagged and partly published when its first run was cut off.
#
# The steps for `--target central`, in order:
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
STEAMSTREET_REPO="https://repo.steamstreet.com"
STEAMSTREET_ACCOUNT="141660060409"
PUBLISH_PROFILE="${AWSKT_PUBLISH_PROFILE:-steamstreet-publisher}"

TARGET="steamstreet"
CHECK=""
SCOPE=""
ASSUME_YES=0
DRY_RUN=0
RESUME=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)     TARGET="${2:-}"; shift 2 ;;
    --target=*)   TARGET="${1#*=}"; shift ;;
    --check)      CHECK="${2:-}"; shift 2 ;;
    --check=*)    CHECK="${1#*=}"; shift ;;
    --scope)      SCOPE="${2:-}"; shift 2 ;;
    --scope=*)    SCOPE="${1#*=}"; shift ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --resume)     RESUME=1; shift ;;
    --skip-check) CHECK="none"; shift ;;
    -h|--help)    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$TARGET" =~ ^(steamstreet|central)$ ]]; then
  echo "--target must be steamstreet or central" >&2
  exit 2
fi

if [[ $RESUME -eq 1 && "$TARGET" != "steamstreet" ]]; then
  echo "--resume publishes to the Steamstreet repository only; a Central release cannot be resumed this way" >&2
  exit 2
fi

if [[ -z "$CHECK" ]]; then
  if [[ "$TARGET" == "central" ]]; then CHECK="full"; else CHECK="jvm"; fi
fi
if [[ ! "$CHECK" =~ ^(full|jvm|none)$ ]]; then
  echo "--check must be full, jvm or none" >&2
  exit 2
fi

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
if [[ $RESUME -eq 1 ]]; then
  # Resuming publishes a commit that is already tagged and pushed, so it need not be a branch tip.
  RESUME_TAG="$(git tag --points-at HEAD | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)"
  [[ -n "$RESUME_TAG" ]] || die "--resume needs HEAD to carry a release tag (vX.Y.Z); check the tag out first"
  git ls-remote --tags origin "$RESUME_TAG" | grep -q . || die "$RESUME_TAG is not on origin"
else
  git fetch --quiet origin "$BRANCH" || die "could not fetch origin/$BRANCH"

  if [[ -n "$(git rev-list "origin/$BRANCH..HEAD")" ]]; then
    die "$BRANCH has unpushed commits; push them first so the tag refers to a commit on origin"
  fi
fi

GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
read_prop() { grep -E "^$1=" "$GRADLE_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true; }

# The publishing profile is handed to the publishing steps alone, as AWS_PROFILE, with any keys the
# shell has removed so that they cannot take precedence over it. It holds a static key, which the AWS
# SDK inside Gradle reads from ~/.aws/credentials; an SSO profile would not work there.
#
# It is never exported as AWS_ACCESS_KEY_ID: the live AWS tests (docs/live-smoke.md) run whenever
# that variable is set, and with the publisher's key, which can only write the Maven bucket, they
# fail. That stopped the first release made this way, before anything was uploaded.
command -v aws >/dev/null || die "the AWS CLI is required to publish to the Steamstreet repository"
publishing() {
  env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    AWS_PROFILE="$PUBLISH_PROFILE" "$@"
}
# The checks run with no AWS credentials at all, so they stay offline whatever the shell holds.
offline() {
  env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE "$@"
}
PUBLISH_ACCOUNT="$(publishing aws sts get-caller-identity --query Account --output text 2>/dev/null)" || \
  die "no working credentials in AWS profile '$PUBLISH_PROFILE'; see infrastructure/README.md"
[[ "$PUBLISH_ACCOUNT" == "$STEAMSTREET_ACCOUNT" ]] || \
  die "profile '$PUBLISH_PROFILE' is in account $PUBLISH_ACCOUNT, not the Steamstreet account $STEAMSTREET_ACCOUNT"

if [[ "$TARGET" == "central" ]]; then
  CENTRAL_USER="${MAVEN_CENTRAL_USERNAME:-$(read_prop mavenCentralUsername)}"
  CENTRAL_PASS="${MAVEN_CENTRAL_PASSWORD:-$(read_prop mavenCentralPassword)}"

  [[ -n "$CENTRAL_USER" && -n "$CENTRAL_PASS" ]] || \
    die "mavenCentralUsername/mavenCentralPassword not found in $GRADLE_PROPS"
  [[ -n "$(read_prop signing.keyId)" ]] || \
    die "signing.keyId not found in $GRADLE_PROPS; Central rejects unsigned artifacts"

  TOKEN="$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_PASS" | base64)"
fi

info "target:      $TARGET"
info "check:       $CHECK"
if [[ $RESUME -eq 1 ]]; then
  info "resuming:    $RESUME_TAG at HEAD"
else
  info "branch:      $BRANCH (in sync with origin)"
fi
info "publishing:  AWS profile $PUBLISH_PROFILE"
[[ "$TARGET" == "central" ]] && info "central:     credentials present"

api_get()  { curl -fsS -u "$CENTRAL_USER:$CENTRAL_PASS" -H "Accept: application/json" --max-time 120 "$@"; }
api_post() { curl -fsS -X POST -H "Authorization: Bearer $TOKEN" --max-time 300 "$@"; }

# --- 2. version --------------------------------------------------------------

say "Resolving version"

# Expanded below as ${SCOPE_ARG[@]+...}, never plainly: macOS ships bash 3.2, which under `set -u`
# treats an empty array's "${SCOPE_ARG[@]}" as an unbound variable and exits. That stopped the
# first real run of this script here, with no --scope given.
SCOPE_ARG=()
[[ -n "$SCOPE" ]] && SCOPE_ARG=(-Prelease.scope="$SCOPE")

# Every Gradle run that builds the release passes these, so they all agree on the version. When
# resuming, nebula takes it from the tag at HEAD instead of inferring the next one.
if [[ $RESUME -eq 1 ]]; then
  VERSION_ARGS=(-Prelease.useLastTag=true -Prelease.stage=final)
else
  VERSION_ARGS=(-Prelease.stage=final ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"})
fi

VERSION="$(./gradlew properties "${VERSION_ARGS[@]}" --console=plain -q 2>/dev/null \
  | grep -E '^version:' | head -1 | awk '{print $2}')"

[[ -n "$VERSION" ]] || die "could not determine the version nebula would use"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "resolved version '$VERSION' is not a release version; check --scope and the branch name"

if [[ $RESUME -eq 1 ]]; then
  [[ "v$VERSION" == "$RESUME_TAG" ]] || die "nebula resolved $VERSION, but HEAD is tagged $RESUME_TAG"
  CHECK="none"
else
  git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && \
    die "tag v$VERSION already exists; this version has been released (use --resume to finish publishing it)"
fi

info "version: $VERSION"

# --- 3. check ----------------------------------------------------------------

case "$CHECK" in
  none)
    say "Skipping the check (--check none)"
    ;;
  jvm)
    say "Running the JVM tests"
    info "jvmTest in the multiplatform modules and test in the JVM-only ones; native tests and the ABI dumps are not checked"
    offline ./gradlew jvmTest test --console=plain || die "the JVM tests failed; nothing has been uploaded"
    ;;
  full)
    say "Running clean check"
    info "this takes roughly 25 minutes; every target is compiled and the ABI dumps verified"
    offline ./gradlew clean check --console=plain || die "check failed; nothing has been uploaded"
    ;;
esac

if [[ $DRY_RUN -eq 1 ]]; then
  say "Dry run complete"
  info "would have released $VERSION from $BRANCH to $TARGET"
  exit 0
fi

# Waits for each path to answer 200 at the Steamstreet repository. CloudFront holds a 404 for about
# ten seconds, so a path asked for a moment before its upload landed answers again shortly after.
verify_steamstreet() {
  local missing=1 path code
  for _ in $(seq 1 18); do
    missing=0
    for path in "$@"; do
      code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$STEAMSTREET_REPO/$path")"
      [[ "$code" == "200" ]] || missing=1
    done
    [[ $missing -eq 0 ]] && break
    sleep 10
  done
  for path in "$@"; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$STEAMSTREET_REPO/$path")"
    info "HTTP $code  $STEAMSTREET_REPO/$path"
  done
  [[ $missing -eq 0 ]]
}

STEAMSTREET_PROBES=(
  "com/steamstreet/awskt/logging/$VERSION/logging-$VERSION.pom"
  "com/steamstreet/awskt/aws-core-jvm/$VERSION/aws-core-jvm-$VERSION.pom"
  "com/steamstreet/awskt/gradle-plugin/$VERSION/gradle-plugin-$VERSION.pom"
)

if [[ "$TARGET" == "steamstreet" ]]; then
  if [[ $RESUME -eq 1 ]]; then
    say "Publishing $VERSION to the Steamstreet repository again (--resume)"
    confirm "Upload $VERSION, already tagged, to $STEAMSTREET_REPO?" || die "aborted before upload"
  else
    say "Releasing $VERSION to the Steamstreet repository"
    confirm "Upload $VERSION to $STEAMSTREET_REPO, then tag v$VERSION?" || die "aborted before upload"
  fi

  publishing ./gradlew publishAllPublicationsToSteamstreetRepository "${VERSION_ARGS[@]}" --console=plain || \
    die "the upload failed; nothing was tagged, so run the release again to finish it"

  say "Publishing the Gradle plugin at $VERSION"
  info 'gradle-plugin is an includeBuild, so the root build does not cover it'
  publishing ./gradlew --project-dir gradle-plugin -Pawskt.pluginVersion="$VERSION" \
    publishAllPublicationsToSteamstreetRepository --console=plain || \
    die "the plugin upload failed; nothing was tagged, so run the release again to finish it"

  say "Checking the artifacts answer at $STEAMSTREET_REPO"
  verify_steamstreet "${STEAMSTREET_PROBES[@]}" || \
    die "not every probe answered; nothing was tagged. Check s3://steamstreet-repository/maven/release"

  if [[ $RESUME -eq 0 ]]; then
    # Tagged last, so that a tag means a complete release. The message matches nebula's own tags.
    say "Tagging v$VERSION"
    git tag -a "v$VERSION" -m "Release of $VERSION" || die "could not create tag v$VERSION"
    git push origin "v$VERSION" || die "could not push tag v$VERSION; push it by hand"
    info "tagged and pushed v$VERSION"
  fi

  say "Release complete: $VERSION"
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

publishing ./gradlew final -Pawskt.publishTarget=central ${SCOPE_ARG[@]+"${SCOPE_ARG[@]}"} --console=plain || \
  die "the release build failed; check whether artifacts were uploaded and whether v$VERSION was tagged before retrying"

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "release finished but tag v$VERSION was not created"
git ls-remote --tags origin "v$VERSION" | grep -q . || die "tag v$VERSION was not pushed to origin"
info "tagged and pushed v$VERSION"

say "Publishing the Gradle plugin at $VERSION"
info 'gradle-plugin is an includeBuild, so the final task does not cover it'
publishing ./gradlew --project-dir gradle-plugin -Pawskt.pluginVersion="$VERSION" \
  publishToSonatype closeSonatypeStagingRepository publishAllPublicationsToSteamstreetRepository \
  --console=plain || die "the plugin build failed to upload"

say "Checking the artifacts answer at $STEAMSTREET_REPO"
verify_steamstreet "${STEAMSTREET_PROBES[@]}" || \
  info "not every probe answered at $STEAMSTREET_REPO; Central continues regardless"

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
