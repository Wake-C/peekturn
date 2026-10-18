#!/bin/sh
set -eu

usage() {
    printf '%s\n' 'Usage: tools/package-apk.sh' \
        'Run debug unit tests, lint and assembly; collect a versioned APK, checksum and receipt.' \
        'Requires JDK 17, Android SDK, Python 3 and Git. No device or provider credentials needed.'
}
case "${1-}" in
    -h|--help) usage; exit 0 ;;
esac
if [ "$#" -ne 0 ]; then usage >&2; exit 2; fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
fail() { printf 'Packaging failed: %s\n' "$*" >&2; exit 1; }
for tool in python3 git; do
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
else
    JAVA=java
    JAVAC=javac
fi
java_version=$("$JAVA" -version 2>&1) || fail 'Java is unavailable.'
javac_version=$("$JAVAC" -version 2>&1) || fail 'A full JDK, not only a JRE, is required.'
printf '%s\n' "$java_version" | grep -Eq 'version "17([."-])' || fail 'Use JDK 17.'
printf '%s\n' "$javac_version" | grep -Eq '^javac 17([.-]|$)' || fail 'Use JDK 17 javac.'

# Match Gradle's local SDK configuration before falling back to environment variables.
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
revision=$(git rev-parse --verify HEAD) || fail 'Run from a Git checkout with a commit.'
worktree_status=$(git status --porcelain --untracked-files=normal) || fail 'Cannot read Git worktree status.'
dirty=false
if [ -n "$worktree_status" ]; then dirty=true; fi

./gradlew --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

delivery_root="$ROOT/app/build/outputs/delivery"
mkdir -p "$delivery_root"
delivery=$(mktemp -d "$delivery_root/package.XXXXXX")
complete=false
cleanup() {
    if [ "$complete" = false ]; then rm -rf -- "$delivery"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

python3 - "$delivery" "$revision" "$dirty" <<'PY'
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

out = Path(sys.argv[1])
source_dir = Path('app/build/outputs/apk/debug').resolve()
metadata = json.loads((source_dir / 'output-metadata.json').read_text())
if metadata.get('variantName') != 'debug':
    raise SystemExit('Expected debug APK metadata.')
elements = metadata.get('elements', [])
if len(elements) != 1 or elements[0].get('filters'):
    raise SystemExit('Expected one universal APK; split APK delivery needs an explicit workflow.')
element = elements[0]
application_id = metadata.get('applicationId', '')
version_name = element.get('versionName', '')
version_code = element.get('versionCode')
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', application_id):
    raise SystemExit('Invalid application ID in APK metadata.')
if not isinstance(version_name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version_name):
    raise SystemExit('Unsafe or missing version name in APK metadata.')
if type(version_code) is not int or version_code < 1:
    raise SystemExit('Invalid version code in APK metadata.')
source = (source_dir / element['outputFile']).resolve()
if source.parent != source_dir or source.suffix != '.apk' or not source.is_file() or source.stat().st_size == 0:
    raise SystemExit('Expected a nonempty APK inside the debug output directory.')
apk = out / f'margin-{version_name}-{version_code}-debug.apk'
shutil.copyfile(source, apk)
hasher = hashlib.sha256()
with apk.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        hasher.update(chunk)
digest = hasher.hexdigest()
(out / f'{apk.name}.sha256').write_text(f'{digest}  {apk.name}\n')
receipt = {
    'applicationId': application_id,
    'versionName': version_name,
    'versionCode': version_code,
    'variant': 'debug',
    'apk': apk.name,
    'sha256': digest,
    'gitRevision': sys.argv[2],
    'dirtyWorktree': sys.argv[3] == 'true',
    'packagedAtUtc': datetime.now(timezone.utc).isoformat(),
    'checks': {'unitTests': 'passed', 'lint': 'passed', 'assembly': 'passed'},
    'androidRuntimeVerification': 'not_run',
}
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(f'APK: {apk}')
print(f'Package: {application_id} {version_name} ({version_code}), debug')
print(f'SHA-256: {digest}')
print(f'Receipt: {out / "receipt.json"}')
print('Android runtime verification: not run. Install/launch and feature acceptance are separate checks.')
PY
complete=true
