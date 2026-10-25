"""Local signing/packaging orchestration, without a real SDK, Gradle or credentials."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'local-release.sh'
SIGNER = 'cdd2c4fb1d83f3041ac3c5c4b08dc85214313abe72bfc977cd584713d3ecbbc2'


class LocalReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='peekturn-local-release-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'checkout with spaces'
        (self.root / 'tools').mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / 'tools/local-release.sh')
        (self.root / '.gitignore').write_text('app/build/\n')
        self.sdk = self.root / 'android sdk'
        bt = self.sdk / 'build-tools/35.0.0'
        bt.mkdir(parents=True)
        self.executable(bt / 'apksigner', f'''#!/bin/sh
[ "${{BAD_SIGNATURE:-0}}" = 0 ] || exit 1
printf 'Signer #1 certificate SHA-256 digest: %s\\n' "${{SIGNER_OVERRIDE:-{SIGNER}}}"
''')
        self.executable(bt / 'aapt2', '''#!/bin/sh
echo "package: name='app.peekturn.reader' versionCode='9' versionName='2.4.1'"
[ "${DEBUGGABLE:-0}" = 0 ] || echo 'application-debuggable'
''')
        (self.root / 'local.properties').write_text(f'sdk.dir={self.sdk}\n')
        jdk = self.root / 'jdk'
        (jdk / 'bin').mkdir(parents=True)
        self.executable(jdk / 'bin/java', '#!/bin/sh\necho \'openjdk version "17.0.14"\' >&2\n')
        self.executable(self.root / 'gradlew', '''#!/bin/sh
printf '%s\\n' "$@" > gradle-args.txt
[ "${FAIL_BUILD:-0}" = 0 ] || exit 19
''')
        store = Path(self.temp.name) / 'external-key.jks'
        store.write_bytes(b'fixture-key')
        self.env = os.environ.copy()
        self.env.update(JAVA_HOME=str(jdk), PEEKTURN_STORE_FILE=str(store),
                        PEEKTURN_STORE_PASSWORD='fixture-secret', PEEKTURN_KEY_PASSWORD='fixture-secret')
        for key in ['BAD_SIGNATURE', 'DEBUGGABLE', 'FAIL_BUILD', 'SIGNER_OVERRIDE']:
            self.env.pop(key, None)
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
        directory = self.root / 'app/build/outputs/apk/release'
        directory.mkdir(parents=True)
        (directory / 'app-release.apk').write_bytes(b'fixture-release-apk')
        self.metadata = directory / 'output-metadata.json'
        self.metadata.write_text(json.dumps({'variantName': 'release', 'applicationId': 'app.peekturn.reader',
            'elements': [{'versionName': '2.4.1', 'versionCode': 9, 'filters': [], 'outputFile': 'app-release.apk'}]}))

    @staticmethod
    def executable(path, source):
        path.write_text(source)
        path.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.STDOUT)

    def run_package(self, *args):
        return subprocess.run(['sh', str(self.root / 'tools/local-release.sh'), *args], cwd=self.temp.name,
                              env=self.env, capture_output=True, text=True)

    def deliveries(self):
        return list((self.root / 'app/build/outputs/delivery').glob('local-release.*'))

    def test_build_only_and_honest_receipt_from_dirty_checkout(self):
        (self.root / 'uncommitted.txt').write_text('keep this')
        result = self.run_package()
        self.assertEqual(result.returncode, 0, result.stderr)
        out, = self.deliveries()
        receipt = json.loads((out / 'receipt.json').read_text())
        self.assertTrue(receipt['dirtyWorktree'])
        self.assertEqual(receipt['checks']['unitTests'], 'not_run')
        self.assertEqual(receipt['checks']['lint'], 'not_run')
        self.assertEqual(receipt['androidRuntimeVerification'], 'not_run')
        self.assertEqual(receipt['signerCertificateSha256'], SIGNER)
        self.assertNotIn('fixture-secret', json.dumps(receipt) + result.stdout + result.stderr)
        apk = out / receipt['apk']
        self.assertEqual(receipt['apkSha256'], hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual((self.root / 'gradle-args.txt').read_text().splitlines(), [
            '--console=plain', ':app:assembleRelease', '-x', ':app:lintVitalRelease',
            '-x', ':app:lintVitalAnalyzeRelease', '-x', ':app:lintVitalReportRelease'])
        self.assertEqual((self.root / 'uncommitted.txt').read_text(), 'keep this')

    def test_build_failure_does_not_collect_stale_output(self):
        self.env['FAIL_BUILD'] = '1'
        self.assertEqual(self.run_package().returncode, 19)
        self.assertEqual(self.deliveries(), [])

    def test_unsigned_wrong_signer_or_debuggable_output_is_rejected(self):
        for key, value in [('BAD_SIGNATURE', '1'), ('SIGNER_OVERRIDE', '0' * 64), ('DEBUGGABLE', '1')]:
            with self.subTest(key=key):
                self.env[key] = value
                self.assertNotEqual(self.run_package().returncode, 0)
                self.assertEqual(self.deliveries(), [])
                self.env.pop(key)

    def test_metadata_cannot_select_a_split_or_escape_the_release_directory(self):
        original = json.loads(self.metadata.read_text())
        for override in [{'versionName': '../../escape'}, {'filters': [{'filterType': 'ABI'}]},
                         {'outputFile': '../outside.apk'}]:
            with self.subTest(override=override):
                metadata = json.loads(json.dumps(original))
                metadata['elements'][0].update(override)
                self.metadata.write_text(json.dumps(metadata))
                self.assertNotEqual(self.run_package().returncode, 0)
                self.assertEqual(self.deliveries(), [])

    def test_help_and_invalid_arguments_do_not_build(self):
        self.assertEqual(self.run_package('--help').returncode, 0)
        self.assertEqual(self.run_package('--debug').returncode, 2)
        self.assertFalse((self.root / 'gradle-args.txt').exists())


if __name__ == '__main__':
    unittest.main()
