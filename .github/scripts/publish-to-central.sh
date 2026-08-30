#!/usr/bin/env bash
#
# Upload a Maven bundle to the Central Publisher Portal and wait for its verdict.
#
# The Portal does not take a deploy over the wire. It takes ONE zip in Maven
# repository layout, validates the whole thing at once, and only then makes it
# visible — which is why the Gradle side publishes into a local repository
# directory (`publishAllPublicationsToCentralBundleRepository` writes
# build/central-bundle) and this script turns that directory into the zip.
#
# Usage:
#   .github/scripts/publish-to-central.sh \
#       --bundle-dir build/central-bundle \
#       --name "larkbatis 0.1.0" \
#       [--publishing-type USER_MANAGED|AUTOMATIC] \
#       [--timeout 1800] \
#       [--dry-run]
#
# Environment:
#   CENTRAL_USERNAME / CENTRAL_PASSWORD — a Portal *user token* pair, generated
#   at https://central.sonatype.com/account, not the portal login.

set -euo pipefail

API="https://central.sonatype.com/api/v1/publisher"

bundle_dir=""
name=""
publishing_type="USER_MANAGED"
timeout_seconds=1800
dry_run=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --bundle-dir)       bundle_dir="$2"; shift 2 ;;
        --name)             name="$2"; shift 2 ;;
        --publishing-type)  publishing_type="$2"; shift 2 ;;
        --timeout)          timeout_seconds="$2"; shift 2 ;;
        --dry-run)          dry_run=true; shift ;;
        -h|--help)          sed -n '2,24p' "$0"; exit 0 ;;
        *)                  echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -n "$bundle_dir" ] || { echo "--bundle-dir is required" >&2; exit 2; }
[ -n "$name" ]       || { echo "--name is required" >&2; exit 2; }

case "$publishing_type" in
    USER_MANAGED|AUTOMATIC) ;;
    *) echo "--publishing-type must be USER_MANAGED or AUTOMATIC, got '$publishing_type'" >&2; exit 2 ;;
esac

if [ ! -d "$bundle_dir" ]; then
    echo "bundle directory does not exist: $bundle_dir" >&2
    echo "run ./gradlew publishAllPublicationsToCentralBundleRepository first" >&2
    exit 1
fi

# --- shape the directory into a deployment bundle --------------------------
#
# Gradle writes a maven-metadata.xml per artifact when it publishes to a file
# repository. It has no place in a deployment bundle: Central derives its own
# metadata from what is actually published, and this copy would describe only
# the single version this one build produced.

removed_metadata=$(find "$bundle_dir" -name 'maven-metadata.xml*' -type f -print -delete | wc -l | tr -d ' ')
if [ "$removed_metadata" != "0" ]; then
    echo "Removed $removed_metadata local maven-metadata file(s) from the bundle"
fi

# Every artifact needs a detached signature, and finding that out here beats
# finding it out after the upload: a bundle that fails validation still occupies
# a deployment that somebody has to go and drop by hand.
missing_signatures=0
while IFS= read -r artifact; do
    if [ ! -f "$artifact.asc" ]; then
        echo "::error::no signature for ${artifact#"$bundle_dir"/}"
        missing_signatures=$((missing_signatures + 1))
    fi
done < <(find "$bundle_dir" -type f \
    ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512')

if [ "$missing_signatures" -ne 0 ]; then
    echo "$missing_signatures artifact(s) are unsigned — is SIGNING_KEY set?" >&2
    exit 1
fi

artifact_count=$(find "$bundle_dir" -name '*.jar' -o -name '*.pom' | wc -l | tr -d ' ')
echo "Bundle: $artifact_count artifacts and POMs, all signed"
find "$bundle_dir" -name '*.pom' | sed "s|$bundle_dir/||" | sort

zip_path="$(cd "$(dirname "$bundle_dir")" && pwd)/central-bundle.zip"
rm -f "$zip_path"
(cd "$bundle_dir" && zip -qr "$zip_path" .)
echo "Zipped to $zip_path ($(du -h "$zip_path" | cut -f1))"

if [ "$dry_run" = true ]; then
    echo "--dry-run: built the bundle, uploading nothing"
    exit 0
fi

: "${CENTRAL_USERNAME:?CENTRAL_USERNAME is not set}"
: "${CENTRAL_PASSWORD:?CENTRAL_PASSWORD is not set}"

# The Portal wants base64(username:password) behind `Bearer`, not HTTP Basic.
token=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
encoded_name=$(jq -rn --arg value "$name" '$value|@uri')

echo "Uploading '$name' with publishingType=$publishing_type"
http_body_file=$(mktemp)
http_code=$(curl -sS -o "$http_body_file" -w '%{http_code}' \
    --request POST \
    --header "Authorization: Bearer $token" \
    --form "bundle=@$zip_path" \
    "$API/upload?name=$encoded_name&publishingType=$publishing_type")

if [ "$http_code" != "201" ]; then
    echo "::error::upload failed with HTTP $http_code"
    cat "$http_body_file"
    rm -f "$http_body_file"
    exit 1
fi

deployment_id=$(tr -d '[:space:]' < "$http_body_file")
rm -f "$http_body_file"
echo "Deployment id: $deployment_id"
echo "Portal: https://central.sonatype.com/publishing/deployments"

# --- wait for the verdict --------------------------------------------------
#
# AUTOMATIC runs to PUBLISHED. USER_MANAGED stops at VALIDATED and waits for a
# human to press Publish in the Portal — that is a success here, not a timeout.

deadline=$((SECONDS + timeout_seconds))
previous_state=""

while :; do
    status=$(curl -sS --request POST \
        --header "Authorization: Bearer $token" \
        "$API/status?id=$deployment_id")
    state=$(printf '%s' "$status" | jq -r '.deploymentState // "UNKNOWN"')

    if [ "$state" != "$previous_state" ]; then
        echo "  state: $state"
        previous_state="$state"
    fi

    case "$state" in
        FAILED)
            echo "::error::deployment $deployment_id failed validation"
            printf '%s' "$status" | jq '.errors // .'
            echo "Leave it in place if you are opening a support request — the files are the evidence."
            exit 1
            ;;
        PUBLISHED)
            echo "Published to Maven Central."
            printf '%s' "$status" | jq -r '.purls[]? | "  " + .'
            exit 0
            ;;
        VALIDATED)
            if [ "$publishing_type" = "USER_MANAGED" ]; then
                echo "Validated and waiting for you to press Publish:"
                echo "  https://central.sonatype.com/publishing/deployments"
                printf '%s' "$status" | jq -r '.purls[]? | "  " + .'
                exit 0
            fi
            ;;
        PENDING|VALIDATING|PUBLISHING) ;;
        *)
            echo "::warning::unrecognised deploymentState '$state'"
            ;;
    esac

    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "::error::still $state after ${timeout_seconds}s — check the Portal"
        exit 1
    fi
    sleep 15
done
