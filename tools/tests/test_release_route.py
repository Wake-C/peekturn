"""Exercise the GitHub publication command against a disposable Git remote."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]


class ReleaseRouteTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / 'checkout'
        self.remote = Path(temp.name) / 'origin.git'
        self.root.mkdir()
        self.git('init', '-q', '-b', 'main')
        self.git('init', '-q', '--bare', str(self.remote))
        self.git('remote', 'add', 'origin', str(self.remote))
        (self.root / 'tools').mkdir()
        shutil.copy2(TOOLS / 'release.sh', self.root / 'tools/release.sh')
        self.git('add', '.')
        self.git('commit', '-qm', 'release candidate')
        self.tag = 'v2.4.0'
        self.git('tag', '-a', self.tag, '-m', 'Release')

    def git(self, *args):
        return subprocess.check_output(
            ['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
             '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false', *args],
            cwd=self.root, text=True).strip()

    def publish(self):
        return subprocess.run([self.root / 'tools/release.sh', 'github', self.tag],
                              env={**os.environ, 'PEEKTURN_STORE_FILE': str(self.root / 'no-keystore')},
                              cwd=self.root, capture_output=True, text=True)

    def test_publishes_only_main_and_the_requested_tag_without_local_signing(self):
        self.git('config', 'push.followTags', 'true')
        self.git('tag', '-a', 'v1.0.0', '-m', 'Unrelated local tag')
        (self.root / 'untracked-prototype.html').touch()
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git('--git-dir', str(self.remote), 'rev-parse', 'main'), self.git('rev-parse', 'main'))
        self.assertEqual(self.git('--git-dir', str(self.remote), 'tag', '--list'), self.tag)
        self.assertEqual(self.git('--git-dir', str(self.remote), 'rev-parse', self.tag), self.git('rev-parse', self.tag))

    def test_stale_tag_does_not_publish_either_ref(self):
        self.git('commit', '--allow-empty', '-qm', 'next version')
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('main', result.stderr)
        self.assertEqual(self.git('--git-dir', str(self.remote), 'for-each-ref', '--format=%(refname)'), '')
