#!/bin/sh
# Shared Gradle invocation for the two local signing commands.
set -eu
if [ -z "${PEEKTURN_STORE_PASSWORD:-}" ]; then
    service=com.peekturn.reader.upload-key
    account=upload
    PEEKTURN_STORE_PASSWORD=$(security find-generic-password -s "$service" -a "$account" -w 2>/dev/null) || {
        status=$?
        printf 'Cannot read macOS Keychain service %s (account %s, security exit %s). Check that the login keychain is unlocked and allows access to this entry.\n' \
            "$service" "$account" "$status" >&2
        exit 1
    }
    if [ -z "$PEEKTURN_STORE_PASSWORD" ]; then
        printf 'Empty signing password in macOS Keychain service %s (account %s).\n' "$service" "$account" >&2
        exit 1
    fi
fi
export PEEKTURN_STORE_PASSWORD
export PEEKTURN_KEY_PASSWORD="${PEEKTURN_KEY_PASSWORD:-$PEEKTURN_STORE_PASSWORD}"
exec ./gradlew "$@"
