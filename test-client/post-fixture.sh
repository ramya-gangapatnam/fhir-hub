#!/usr/bin/env sh
# POSIX-only — bash-isms intentionally avoided so this runs identically on macOS, Linux,
# and Windows (Git Bash). The 5-minute onboarding path (SC-008) calls this script with
# nothing but curl on PATH; no jq, no awk-only-on-GNU. Exit codes mirror curl's.
#
# Usage:
#   test-client/post-fixture.sh fixtures/adt-a01-good.hl7
#   test-client/post-fixture.sh --base-url http://localhost:8080 fixtures/adt-a01-good.hl7
#
# Environment:
#   FHIR_HUB_BASE_URL    overrides the default http://localhost:8080
#   FHIR_HUB_AUTH_TOKEN  the bearer token; defaults to the demo token "demo-token"
#
# This client deliberately runs OUTSIDE the JVM so the README's quickstart never has to
# discuss Gradle. Provenance: hand-authored.

set -eu

BASE_URL="${FHIR_HUB_BASE_URL:-http://localhost:8080}"
AUTH_TOKEN="${FHIR_HUB_AUTH_TOKEN:-demo-token}"

usage() {
    printf 'Usage: %s [--base-url URL] <fixture-path>\n' "$0" >&2
    printf '\nExample: %s fixtures/adt-a01-good.hl7\n' "$0" >&2
    exit 2
}

FIXTURE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --base-url)
            shift
            [ $# -gt 0 ] || usage
            BASE_URL="$1"
            ;;
        -h|--help)
            usage
            ;;
        --*)
            printf 'Unknown option: %s\n' "$1" >&2
            usage
            ;;
        *)
            if [ -z "$FIXTURE" ]; then
                FIXTURE="$1"
            else
                printf 'Unexpected extra argument: %s\n' "$1" >&2
                usage
            fi
            ;;
    esac
    shift
done

if [ -z "$FIXTURE" ]; then
    usage
fi

# Fixtures live alongside this script. Resolve relative paths against the script directory
# so the call site can `cd` anywhere and still pass `fixtures/adt-a01-good.hl7`.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$FIXTURE" in
    /*) FIXTURE_PATH="$FIXTURE" ;;
    *)  FIXTURE_PATH="$SCRIPT_DIR/$FIXTURE" ;;
esac

if [ ! -f "$FIXTURE_PATH" ]; then
    printf 'Fixture not found: %s\n' "$FIXTURE_PATH" >&2
    exit 2
fi

printf '→ POST %s/ingest/hl7v2  (fixture: %s)\n' "$BASE_URL" "$FIXTURE_PATH" >&2

# HL7 v2 mandates \r segment terminators. Fixture files are stored as plain text (LF or
# CRLF depending on checkout); normalize to CR-only before posting so the parser is happy.
# tr is in every POSIX environment, including Git Bash on Windows.
HL7_BODY=$(tr -d '\r' < "$FIXTURE_PATH" | tr '\n' '\r')

# --fail-with-body so non-2xx status codes both print the error envelope and exit non-zero.
exec curl \
    --fail-with-body \
    --silent \
    --show-error \
    --request POST \
    --url "$BASE_URL/ingest/hl7v2" \
    --header "Authorization: Bearer $AUTH_TOKEN" \
    --header "Content-Type: application/hl7-v2" \
    --data-binary "$HL7_BODY"
