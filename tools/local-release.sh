#!/bin/sh
set -eu
# Local iteration only. Keep the public-release gate in release.sh unchanged.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec python3 - "$ROOT" "$@" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

ROOT = Path(sys.argv[1])
EXPECTED_SIGNER = 'cdd2c4fb1d83f3041ac3c5c4b08dc85214313abe72bfc977cd584713d3ecbbc2'
GRADLE_TASKS = ['--console=plain', ':app:assembleRelease',
                '-x', ':app:lintVitalRelease', '-x', ':app:lintVitalAnalyzeRelease',
                '-x', ':app:lintVitalReportRelease']

def capture(command, env):
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{Path(command[0]).name} check failed')
    return result.stdout

def main():
    args = sys.argv[2:]
    if args in (['--help'], ['-h']):
        print('Usage: tools/local-release.sh\n'
              'Build and verify a signed production APK from the current worktree.\n'
              'No tests, lint, AAB, version bump, install, commit, push or publish.\n'
              'Uses the existing release key and macOS Keychain, or signing environment variables.')
        return 0
    if args:
        print('Unsupported arguments. Use --help.', file=sys.stderr)
        return 2
    os.umask(0o077)
    env = os.environ.copy()
    if not env.get('JAVA_HOME') and sys.platform == 'darwin':
        env['JAVA_HOME'] = capture(['/usr/libexec/java_home', '-v', '17'], env).strip()
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
    version = subprocess.run([java, '-version'], env=env, capture_output=True, text=True)
    if version.returncode or not re.search(r'version "17[.\"]', version.stdout + version.stderr):
        raise RuntimeError('Use JDK 17')
    local = ROOT / 'local.properties'
    match = re.search(r'^\s*sdk\.dir\s*[=:]\s*(.*?)\s*$', local.read_text(), re.M) if local.exists() else None
    sdk = re.sub(r'\\([\\:= ])', r'\1', match[1]) if match else ''
    sdk = sdk or env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise RuntimeError('Configure the Android SDK in local.properties or ANDROID_HOME')
    sdk = Path(sdk)
    tools = [d for d in (sdk / 'build-tools').glob('*') if re.fullmatch(r'\d+\.\d+\.\d+', d.name)
             and (d / 'apksigner').is_file() and (d / 'aapt2').is_file()]
    if not tools:
        raise RuntimeError('Configure an Android SDK with stable build-tools (apksigner and aapt2)')
    build_tools = max(tools, key=lambda d: tuple(map(int, d.name.split('.'))))
    env['ANDROID_HOME'] = env['ANDROID_SDK_ROOT'] = str(sdk.resolve())
    store = Path(env.get('PEEKTURN_STORE_FILE', '~/.config/peekturn/peekturn-upload.jks')).expanduser().resolve()
    if not store.is_file() or ROOT.resolve() in store.parents:
        raise RuntimeError('The release keystore must exist outside the checkout')
    env['PEEKTURN_STORE_FILE'] = str(store)
    env.setdefault('PEEKTURN_KEY_ALIAS', 'peekturn-upload')
    if not env.get('PEEKTURN_STORE_PASSWORD'):
        env['PEEKTURN_STORE_PASSWORD'] = capture(
            ['security', 'find-generic-password', '-s', 'com.peekturn.reader.upload-key', '-a', 'upload', '-w'], env).rstrip('\n')
    if not env['PEEKTURN_STORE_PASSWORD']:
        raise RuntimeError('Release signing password is unavailable')
    env.setdefault('PEEKTURN_KEY_PASSWORD', env['PEEKTURN_STORE_PASSWORD'])
    # No check/test/lint tasks. -x is local to this invocation, not a project-wide setting.
    subprocess.run([str(ROOT / 'gradlew'), *GRADLE_TASKS], cwd=ROOT, env=env, check=True)
    source_dir = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((source_dir / 'output-metadata.json').read_text())
    elements = metadata.get('elements', [])
    if metadata.get('applicationId') != 'app.peekturn.reader' or metadata.get('variantName') != 'release':
        raise RuntimeError('Expected production release metadata')
    if len(elements) != 1 or elements[0].get('filters'):
        raise RuntimeError('Expected one universal APK')
    element = elements[0]
    name, code = element['versionName'], element['versionCode']
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', name) or type(code) is not int or code < 1:
        raise RuntimeError('Invalid package version')
    source = (source_dir / element['outputFile']).resolve()
    if source.parent != source_dir.resolve() or source.suffix != '.apk' or not source.is_file():
        raise RuntimeError('Invalid APK output path')
    delivery_root = ROOT / 'app/build/outputs/delivery'
    delivery_root.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='local-release.', dir=delivery_root))
    try:
        apk = out / f'peekturn-{name}-{code}-release.apk'
        shutil.copyfile(source, apk)
        signature = capture([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], env)
        signer = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature, re.M)
        if not signer or signer[1].lower() != EXPECTED_SIGNER:
            raise RuntimeError('APK does not use the existing release signer')
        badging = capture([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)], env)
        if 'application-debuggable' in badging or not all(value in badging for value in (
                "name='app.peekturn.reader'", f"versionCode='{code}'", f"versionName='{name}'")):
            raise RuntimeError('APK package, version or debuggable flag does not match')
        digest = hashlib.sha256()
        with apk.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        sha = digest.hexdigest()
        (out / (apk.name + '.sha256')).write_text(f'{sha}  {apk.name}\n')
        (out / 'signature.txt').write_text(signature)
        (out / 'package.txt').write_text(badging)
        receipt = {'applicationId': metadata['applicationId'], 'versionName': name, 'versionCode': code,
                   'variant': 'release', 'debuggable': False, 'apk': apk.name, 'apkSha256': sha,
                   'signerCertificateSha256': EXPECTED_SIGNER,
                   'gitRevision': capture(['git', 'rev-parse', 'HEAD'], env).strip(),
                   'dirtyWorktree': bool(capture(['git', 'status', '--porcelain', '--untracked-files=normal'], env).strip()),
                   'packagedAtUtc': datetime.now(timezone.utc).isoformat(),
                   'checks': {'assembly': 'passed', 'signature': 'passed', 'package': 'passed',
                              'unitTests': 'not_run', 'lint': 'not_run'},
                   'androidRuntimeVerification': 'not_run'}
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    except BaseException:
        shutil.rmtree(out)
        raise
    print(f'APK: {apk}\nReceipt: {out / "receipt.json"}\nSHA-256: {sha}')
    print('Signed, non-debuggable production APK. Tests, lint and device checks were not run by this script.')
    return 0

try:
    sys.exit(main())
except subprocess.CalledProcessError as error:
    print('Local release build failed; no APK collected.', file=sys.stderr)
    sys.exit(error.returncode)
except RuntimeError as error:
    print(f'Local release failed: {error}. No APK collected.', file=sys.stderr)
    sys.exit(1)
except (OSError, ValueError, KeyError):
    print('Local release failed: check JDK/SDK, signing configuration and release output. No APK collected.', file=sys.stderr)
    sys.exit(1)
PY
