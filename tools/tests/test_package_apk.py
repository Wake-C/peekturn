"""Packaging orchestration tests; no Android SDK or Gradle downloads required."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'package-apk.sh'


class PackageApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='margin-package-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'checkout with spaces'
        (self.root / 'tools').mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / 'tools/package-apk.sh')
        (self.root / 'gradle/wrapper').mkdir(parents=True)
        (self.root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'fixture')
        (self.root / '.gitignore').write_text('app/build/\n')
        self.sdk = self.root / 'android sdk'
        self.sdk.mkdir()
        (self.root / 'local.properties').write_text(f'sdk.dir={self.sdk}\n')
        java_home = self.root / 'jdk'
        (java_home / 'bin').mkdir(parents=True)
        self.executable(java_home / 'bin/java', '#!/bin/sh\necho \'openjdk version "17.0.14"\' >&2\n')
        self.executable(java_home / 'bin/javac', '#!/bin/sh\necho "javac 17.0.14"\n')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'python3').symlink_to(sys.executable)
        self.executable(self.root / 'gradlew', '''#!/bin/sh
printf '%s\\n' "$@" > gradle-args.txt
[ "${FAIL_BUILD:-0}" = 0 ] || exit 19
''')
        self.env = os.environ.copy()
        self.env.update(JAVA_HOME=str(java_home), PATH=str(self.bin) + os.pathsep + self.env['PATH'])
        for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'FAIL_BUILD'):
            self.env.pop(key, None)
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=Packaging Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
        self.metadata()

    @staticmethod
    def executable(path, content):
        path.write_text(content)
        path.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.STDOUT).decode().strip()

    def metadata(self, **overrides):
        directory = self.root / 'app/build/outputs/apk/debug'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'app-debug.apk').write_bytes(b'fixture-apk-bytes')
        (directory / 'unrelated-old.apk').write_bytes(b'never-collect-this')
        element = {'versionCode': 9, 'versionName': '2.4.1', 'filters': [], 'outputFile': 'app-debug.apk'}
        element.update(overrides)
        metadata = {'variantName': 'debug', 'applicationId': 'app.margin.reader', 'elements': [element]}
        (directory / 'output-metadata.json').write_text(json.dumps(metadata))

    def run_package(self, *args):
        return subprocess.run(['sh', str(self.root / 'tools/package-apk.sh'), *args], cwd=self.temp.name,
                              env=self.env, capture_output=True, text=True)

    def deliveries(self):
        return list((self.root / 'app/build/outputs/delivery').glob('package.*'))

    def test_collects_metadata_selected_apk_with_hash_and_honest_receipt(self):
        result = self.run_package()
        self.assertEqual(result.returncode, 0, result.stderr)
        out, = self.deliveries()
        apk = out / 'margin-2.4.1-9-debug.apk'
        self.assertEqual(apk.read_bytes(), b'fixture-apk-bytes')
        receipt = json.loads((out / 'receipt.json').read_text())
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.assertEqual(receipt['sha256'], digest)
        self.assertEqual((out / f'{apk.name}.sha256').read_text(), f'{digest}  {apk.name}\n')
        self.assertEqual(receipt['gitRevision'], self.git('rev-parse', 'HEAD'))
        self.assertFalse(receipt['dirtyWorktree'])
        self.assertEqual(receipt['androidRuntimeVerification'], 'not_run')
        self.assertEqual(receipt['checks'], {'unitTests': 'passed', 'lint': 'passed', 'assembly': 'passed'})
        self.assertEqual((self.root / 'gradle-args.txt').read_text().splitlines(),
                         ['--console=plain', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'])

    def test_failed_checks_do_not_collect_stale_apk_or_erase_previous_package(self):
        previous = self.root / 'app/build/outputs/delivery/package.previous'
        previous.mkdir(parents=True)
        (previous / 'old.apk').write_bytes(b'previous-delivery')
        self.env['FAIL_BUILD'] = '1'
        result = self.run_package()
        self.assertEqual(result.returncode, 19)
        self.assertEqual(self.deliveries(), [previous])
        self.assertEqual((previous / 'old.apk').read_bytes(), b'previous-delivery')

    def test_dirty_source_is_reported_without_changing_source(self):
        source = self.root / 'uncommitted.txt'
        source.write_text('current changes\n')
        result = self.run_package()
        self.assertEqual(result.returncode, 0, result.stderr)
        out, = self.deliveries()
        self.assertTrue(json.loads((out / 'receipt.json').read_text())['dirtyWorktree'])
        self.assertEqual(source.read_text(), 'current changes\n')

    def test_invalid_metadata_never_publishes_a_package(self):
        (self.root / 'app/build/outputs/apk/outside.apk').write_bytes(b'outside-output-directory')
        for override in ({'versionName': '../../escape'}, {'outputFile': '../outside.apk'},
                         {'filters': [{'filterType': 'ABI', 'value': 'arm64-v8a'}]}):
            with self.subTest(override=override):
                self.metadata(**override)
                result = self.run_package()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.deliveries(), [])

    def test_missing_apk_and_multiple_outputs_never_publish_a_package(self):
        directory = self.root / 'app/build/outputs/apk/debug'
        (directory / 'app-debug.apk').unlink()
        result = self.run_package()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.deliveries(), [])
        self.metadata()
        metadata_path = directory / 'output-metadata.json'
        metadata = json.loads(metadata_path.read_text())
        metadata['elements'].append(dict(metadata['elements'][0]))
        metadata_path.write_text(json.dumps(metadata))
        result = self.run_package()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.deliveries(), [])

    def test_wrong_jdk_fails_before_gradle(self):
        self.executable(self.root / 'jdk/bin/java', '#!/bin/sh\necho \'openjdk version "21.0.1"\' >&2\n')
        result = self.run_package()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Use JDK 17', result.stderr)
        self.assertFalse((self.root / 'gradle-args.txt').exists())

    def test_sdk_can_come_from_environment_without_local_properties(self):
        (self.root / 'local.properties').unlink()
        self.env['ANDROID_HOME'] = str(self.sdk)
        result = self.run_package()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_sdk_fails_before_gradle(self):
        (self.root / 'local.properties').unlink()
        result = self.run_package()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Android SDK configuration', result.stderr)
        self.assertFalse((self.root / 'gradle-args.txt').exists())

    def test_help_and_unsupported_arguments_never_build(self):
        self.assertEqual(self.run_package('--help').returncode, 0)
        self.assertEqual(self.run_package('--release').returncode, 2)
        self.assertFalse((self.root / 'gradle-args.txt').exists())


if __name__ == '__main__':
    unittest.main()
