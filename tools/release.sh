#!/bin/sh
set -eu
umask 077

usage() {
    printf '%s\n' 'Usage: tools/release.sh' \
        'Build a signed release APK and AAB, verify both signatures, and collect checksums plus a receipt.' \
        'The upload keystore stays outside the repository; its password is read from macOS Keychain.' \
        'Requires JDK 17, Android SDK, Python 3, Git, jarsigner and apksigner.'
}
case "${1-}" in
    -h|--help) usage; exit 0 ;;
esac
if [ "$#" -ne 0 ]; then usage >&2; exit 2; fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
fail() { printf 'Release failed: %s\n' "$*" >&2; exit 1; }

for tool in python3 git security shasum; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required."
done
[ -x ./gradlew ] || fail 'The executable Gradle wrapper is missing.'
[ -f gradle/wrapper/gradle-wrapper.jar ] || fail 'The Gradle wrapper JAR is missing.'

if [ -z "${JAVA_HOME:-}" ] && [ "$(uname)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) || fail 'Install JDK 17 or set JAVA_HOME.'
    export JAVA_HOME
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
    JARSIGNER="$JAVA_HOME/bin/jarsigner"
else
    JAVA=java
    JAVAC=javac
    JARSIGNER=jarsigner
fi
java_version=$("$JAVA" -version 2>&1) || fail 'Java is unavailable.'
javac_version=$("$JAVAC" -version 2>&1) || fail 'A full JDK, not only a JRE, is required.'
printf '%s\n' "$java_version" | grep -Eq 'version "17([."-])' || fail 'Use JDK 17.'
printf '%s\n' "$javac_version" | grep -Eq '^javac 17([.-]|$)' || fail 'Use JDK 17 javac.'
command -v "$JARSIGNER" >/dev/null 2>&1 || fail 'jarsigner is required.'

sdk=$(python3 - <<'PY'
import os
from pathlib import Path
import re

local = Path('local.properties')
match = re.search(r'^\s*sdk\.dir\s*[=:]\s*(.*?)\s*$', local.read_text(), re.M) if local.exists() else None
sdk = re.sub(r'\\([\\:= ])', r'\1', match.group(1)) if match else ''
sdk = sdk or os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk or not Path(sdk).is_dir():
    raise SystemExit('Set ANDROID_HOME to an installed Android SDK, or set sdk.dir in local.properties.')
print(Path(sdk).resolve())
PY
) || fail 'Android SDK configuration is unavailable.'
ANDROID_HOME=$sdk
ANDROID_SDK_ROOT=$sdk
export ANDROID_HOME ANDROID_SDK_ROOT
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)
[ -n "$APKSIGNER" ] || fail 'apksigner was not found in the Android SDK build-tools.'

KEYSTORE=${PEEKTURN_STORE_FILE:-$HOME/.config/peekturn/peekturn-upload.jks}
KEY_ALIAS=${PEEKTURN_KEY_ALIAS:-peekturn-upload}
case "$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")" in
    "$ROOT"/*) fail 'The upload keystore must stay outside the repository.' ;;
esac
[ -f "$KEYSTORE" ] || fail "Upload keystore not found at $KEYSTORE. Run the one-time setup first."
STORE_PASSWORD=$(security find-generic-password -s com.peekturn.reader.upload-key -a upload -w 2>/dev/null) || \
    fail 'Upload key password is missing from macOS Keychain.'
[ -n "$STORE_PASSWORD" ] || fail 'Upload key password is empty.'

worktree_status=$(git status --porcelain --untracked-files=normal) || fail 'Cannot read Git worktree status.'
[ -z "$worktree_status" ] || fail 'Release requires a clean worktree. Commit or stash every change first.'
revision=$(git rev-parse --verify HEAD) || fail 'Run from a Git checkout with a commit.'

export PEEKTURN_STORE_FILE="$KEYSTORE"
export PEEKTURN_STORE_PASSWORD="$STORE_PASSWORD"
export PEEKTURN_KEY_ALIAS="$KEY_ALIAS"
export PEEKTURN_KEY_PASSWORD="${PEEKTURN_KEY_PASSWORD:-$STORE_PASSWORD}"

./gradlew --console=plain :app:testDebugUnitTest :app:lintRelease :app:bundleRelease :app:assembleRelease

aab="$ROOT/app/build/outputs/bundle/release/app-release.aab"
apk="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$aab" ] || fail 'The release AAB is missing.'
[ -f "$apk" ] || fail 'The release APK is missing.'
[ -s "$aab" ] || fail 'The release AAB is empty.'
[ -s "$apk" ] || fail 'The release APK is empty.'

"$APKSIGNER" verify --verbose --print-certs "$apk" >/tmp/peekturn-apksigner.txt 2>&1 || \
    fail 'APK signature verification failed.'
signer=$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
[ -n "$signer" ] || fail 'Could not read the APK signer certificate.'
"$JARSIGNER" -verify -certs "$aab" >/tmp/peekturn-jarsigner.txt 2>&1 || \
    fail 'AAB signature verification failed.'
grep -q "jar verified" /tmp/peekturn-jarsigner.txt || fail 'AAB signature verification did not report a verified JAR.'

read -r application_id version_name version_code <<EOF
$(python3 - <<'PY'
import json
from pathlib import Path
metadata = json.loads(Path('app/build/outputs/apk/release/output-metadata.json').read_text())
element = metadata['elements'][0]
print(metadata['applicationId'], element['versionName'], element['versionCode'])
PY
)
EOF
[ "$application_id" = 'app.peekturn.reader' ] || fail "Unexpected applicationId: $application_id"

delivery_root="$ROOT/app/build/outputs/delivery"
mkdir -p "$delivery_root"
delivery=$(mktemp -d "$delivery_root/release.XXXXXX")
complete=false
cleanup() {
    if [ "$complete" = false ]; then rm -rf -- "$delivery"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

apk_name="peekturn-${version_name}-${version_code}-release.apk"
aab_name="peekturn-${version_name}-${version_code}-release.aab"
cp "$apk" "$delivery/$apk_name"
cp "$aab" "$delivery/$aab_name"
apk_digest=$(shasum -a 256 "$delivery/$apk_name" | awk '{print $1}')
aab_digest=$(shasum -a 256 "$delivery/$aab_name" | awk '{print $1}')
printf '%s  %s\n' "$apk_digest" "$apk_name" > "$delivery/$apk_name.sha256"
printf '%s  %s\n' "$aab_digest" "$aab_name" > "$delivery/$aab_name.sha256"

python3 - "$delivery" "$application_id" "$version_name" "$version_code" "$revision" "$apk_digest" "$aab_digest" "$signer" <<'PY'
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

out = Path(sys.argv[1])
receipt = {
    'applicationId': sys.argv[2],
    'versionName': sys.argv[3],
    'versionCode': int(sys.argv[4]),
    'variant': 'release',
    'apk': f'peekturn-{sys.argv[3]}-{sys.argv[4]}-release.apk',
    'aab': f'peekturn-{sys.argv[3]}-{sys.argv[4]}-release.aab',
    'apkSha256': sys.argv[6],
    'aabSha256': sys.argv[7],
    'signerCertificateSha256': sys.argv[8],
    'gitRevision': sys.argv[5],
    'dirtyWorktree': False,
    'packagedAtUtc': datetime.now(timezone.utc).isoformat(),
    'checks': {'unitTests': 'passed', 'lint': 'passed', 'assembly': 'release_passed'},
    'signatureVerification': {'apk': 'passed', 'aab': 'passed'},
    'androidRuntimeVerification': 'not_run',
}
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
PY
complete=true
printf 'APK: %s\n' "$delivery/$apk_name"
printf 'AAB: %s\n' "$delivery/$aab_name"
printf 'SHA-256 APK: %s\n' "$apk_digest"
printf 'SHA-256 AAB: %s\n' "$aab_digest"
printf 'Receipt: %s\n' "$delivery/receipt.json"
printf '%s\n' 'Release signatures verified. Install the APK on a device and complete the release checklist before publishing.'
