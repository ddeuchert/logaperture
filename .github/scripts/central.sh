#!/usr/bin/env bash
# Copyright 2026 David Deuchert
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Bundles, checks and uploads a release to Maven Central through the Central Portal's
# Publisher API - doc/specs/maven-central-publishing.md. Used by .github/workflows/central.yml
# (the dry run) and release.yml (the real publish), so both go through the same steps.
#
#   central.sh bundle <staging-dir> <version> <bundle.zip>
#       Zips the staging repository `mvn -Prelease deploy` wrote, after checking it holds
#       exactly the published files: each one present, signed and checksummed, nothing else.
#   central.sh upload <bundle.zip> <name>      prints the deployment id; never publishes
#   central.sh wait <deployment-id> [published]
#       until Central has validated it, or with 'published' until it is on Maven Central
#       (exit 1 if FAILED)
#   central.sh drop <deployment-id>            discards it: nothing is published
#   central.sh publish <deployment-id>         publishes it - permanent, cannot be undone
#
# upload, wait, drop and publish read the Central Portal user token from
# CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD.

set -euo pipefail

API=https://central.sonatype.com/api/v1/publisher
GROUP_PATH=org/logaperture

die() {
    echo "central.sh: $*" >&2
    exit 1
}

# Every file the bundle must hold, relative to the group directory, for one version.
expected_files() {
    local v=$1
    echo "logaperture-parent/$v/logaperture-parent-$v.pom"
    for a in logaperture-agent logaperture-cli; do
        echo "$a/$v/$a-$v.pom"
        echo "$a/$v/$a-$v.jar"
        echo "$a/$v/$a-$v-sources.jar"
        echo "$a/$v/$a-$v-javadoc.jar"
    done
    echo "logaperture/$v/logaperture-$v.pom"
    echo "logaperture/$v/logaperture-$v.zip"
}

# Checked before any API call, not inside auth_header: a die in $(auth_header) would only end
# the subshell, and curl would go ahead without credentials.
require_token() {
    [[ -n "${CENTRAL_TOKEN_USERNAME:-}" && -n "${CENTRAL_TOKEN_PASSWORD:-}" ]] \
        || die "CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD must be set"
}

auth_header() {
    printf 'Authorization: Bearer %s' "$(printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" | base64 -w0)"
}

cmd_bundle() {
    local staging=$1 version=$2 out=$3
    [[ "$version" != *-SNAPSHOT ]] || die "$version is a snapshot; Central takes release versions only"
    local group="$staging/$GROUP_PATH"
    [[ -d "$group" ]] || die "no $GROUP_PATH in $staging - did 'mvn -Prelease deploy' write there?"

    local missing=0 f
    while read -r f; do
        for suffix in "" .asc .md5 .sha1; do
            if [[ ! -s "$group/$f$suffix" ]]; then
                echo "missing: $f$suffix" >&2
                missing=1
            fi
        done
    done < <(expected_files "$version")
    [[ $missing -eq 0 ]] || die "the staging repository is incomplete"

    # Central rejects a POM without these. <name> must be in each POM: Maven doesn't inherit it.
    # The others may come from the parent POM, which Central resolves.
    local bad_pom=0
    while read -r f; do
        [[ "$f" == *.pom ]] || continue
        python3 - "$group/$f" "$group/logaperture-parent/$version/logaperture-parent-$version.pom" <<'PY' || bad_pom=1
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom, parent = (ET.parse(path).getroot() for path in sys.argv[1:3])
def has(root, e):
    return bool(root.findtext(f"m:{e}", default="", namespaces=ns).strip()
                or root.find(f"m:{e}/*", ns) is not None)
missing = [e for e in ("name", "description", "url", "licenses", "developers", "scm")
           if not (has(pom, e) or (e != "name" and has(parent, e)))]
if missing:
    print(f"{sys.argv[1]}: missing {', '.join(missing)}", file=sys.stderr)
    sys.exit(1)
PY
    done < <(expected_files "$version")
    [[ $bad_pom -eq 0 ]] || die "a POM lacks metadata Maven Central requires"

    # Nothing else may go out: a module that lost its maven.deploy.skip would otherwise be
    # published for good. Checksums of the signatures are allowed but not required.
    local allowed unexpected
    allowed=$(expected_files "$version" | while read -r f; do
        for suffix in "" .asc; do
            for sum in "" .md5 .sha1 .sha256 .sha512; do
                echo "$f$suffix$sum"
            done
        done
    done)
    unexpected=$(cd "$group" && find . -type f ! -name 'maven-metadata*' | sed 's|^\./||' \
        | grep -v -x -F -f <(echo "$allowed") || true)
    [[ -z "$unexpected" ]] || die "unexpected files in the staging repository:"$'\n'"$unexpected"

    rm -f "$out"
    local abs_out
    abs_out=$(realpath -m "$out")
    (cd "$staging" && find "$GROUP_PATH" -type f ! -name 'maven-metadata*' | sort | zip -q -X "$abs_out" -@)
    echo "bundle: $out"
    unzip -l "$out" | awk 'NR > 3 && $4 != "" { print "  " $4 }' | grep -v -E '\.(md5|sha1|sha256|sha512)$'
}

cmd_upload() {
    local bundle=$1 name=$2
    [[ -s "$bundle" ]] || die "no bundle at $bundle"
    local id
    # USER_MANAGED: Central validates the upload and then waits; only 'publish' releases it.
    id=$(curl --silent --show-error --fail-with-body \
        --header "$(auth_header)" \
        --form "bundle=@$bundle" \
        "$API/upload?publishingType=USER_MANAGED&name=$(printf '%s' "$name" | jq -sRr @uri)")
    [[ "$id" =~ ^[0-9a-f-]{36}$ ]] || die "unexpected upload response: $id"
    echo "$id"
}

status() {
    curl --silent --show-error --fail-with-body --request POST \
        --header "$(auth_header)" "$API/status?id=$1"
}

cmd_wait() {
    local id=$1 until=${2:-validated} state response
    [[ "$until" == validated || "$until" == published ]] || die "wait until 'validated' or 'published', not '$until'"
    # Validation takes minutes; publishing can take up to about 30 more.
    for _ in $(seq 1 720); do
        response=$(status "$id")
        state=$(jq -r .deploymentState <<<"$response")
        if [[ "$state" == VALIDATED && "$until" == published ]]; then
            state=PUBLISHING # asked to publish, not yet picked up
        fi
        case "$state" in
            VALIDATED | PUBLISHED)
                echo "deployment $id: $state"
                jq -r '.purls[]? // empty' <<<"$response" | sed 's/^/  /'
                return 0
                ;;
            FAILED)
                echo "deployment $id: FAILED" >&2
                jq '.errors' <<<"$response" >&2
                return 1
                ;;
            PENDING | VALIDATING | PUBLISHING)
                sleep "${CENTRAL_POLL_SECONDS:-5}" # the Portal's own minimum; tests set it to 0
                ;;
            *)
                die "deployment $id: unexpected state '$state': $response"
                ;;
        esac
    done
    die "deployment $id: still $state after an hour"
}

cmd_drop() {
    curl --silent --show-error --fail-with-body --request DELETE \
        --header "$(auth_header)" "$API/deployment/$1"
    echo "deployment $1: dropped"
}

cmd_publish() {
    curl --silent --show-error --fail-with-body --request POST \
        --header "$(auth_header)" "$API/deployment/$1"
    echo "deployment $1: publishing"
}

command=${1:-}
shift || true
case "$command" in
    upload | wait | drop | publish) require_token ;;
esac
case "$command" in
    bundle) [[ $# -eq 3 ]] || die "usage: central.sh bundle <staging-dir> <version> <bundle.zip>"; cmd_bundle "$@" ;;
    upload) [[ $# -eq 2 ]] || die "usage: central.sh upload <bundle.zip> <name>"; cmd_upload "$@" ;;
    wait) [[ $# -ge 1 && $# -le 2 ]] || die "usage: central.sh wait <deployment-id> [published]"; cmd_wait "$@" ;;
    drop) [[ $# -eq 1 ]] || die "usage: central.sh drop <deployment-id>"; cmd_drop "$@" ;;
    publish) [[ $# -eq 1 ]] || die "usage: central.sh publish <deployment-id>"; cmd_publish "$@" ;;
    *) die "usage: central.sh bundle|upload|wait|drop|publish ..." ;;
esac
