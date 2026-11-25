"""Exercise CI selection and inline safety checks without an Android SDK."""

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ci_plan', ROOT / 'tools/ci/plan.py')
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


def workflow_run_block(workflow, step_name):
    """Read the literal shell block under a named step; reject unexpected formatting."""
    lines = (ROOT / '.github/workflows' / workflow).read_text().splitlines()
    start = lines.index(f'      - name: {step_name}')
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith('      - ')), len(lines))
    run = lines.index('        run: |', start, end)
    block = lines[run + 1:end]
    while block and not block[-1].strip():
        block.pop()
    # Comments between steps are not part of the literal block.
    while block and block[-1].startswith('      #'):
        block.pop()
    assert all(not line.strip() or line.startswith('          ') for line in block), step_name
    return '\n'.join(line[10:] for line in block) + '\n'


class GitFixture(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='peekturn-ci-test-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.git('init', '-q', '-b', 'main')
        self.write('README.md', 'Initial documentation\n')
        self.write('app/source.kt', 'fun main() = Unit\n')
        self.commit('initial')
        self.base = self.git('rev-parse', 'HEAD')

    def git(self, *args):
        return subprocess.check_output(
            ['git', '-c', 'user.name=CI Test', '-c', 'user.email=test@example.invalid',
             '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false', *args],
            cwd=self.root, stderr=subprocess.STDOUT, text=True).strip()

    def write(self, name, content):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-qm', message)


class BuildSelectionTest(GitFixture):
    def test_docs_only_push_and_full_pr_diff_skip_android(self):
        self.write('README.md', 'New documentation\n')
        self.write('release-notes/v2.5.1.md', 'Release notes\n')
        self.commit('docs')
        for event in ('push', 'pull_request'):
            with self.subTest(event=event):
                self.assertFalse(plan.requires_android(event, self.base, self.root))

    def test_code_commit_followed_by_docs_still_checks_entire_push_and_pr(self):
        self.write('app/source.kt', 'fun main() = println("changed")\n')
        self.commit('code')
        self.write('README.md', 'Documentation-only last commit\n')
        self.commit('docs')
        for event in ('push', 'pull_request'):
            with self.subTest(event=event):
                self.assertTrue(plan.requires_android(event, self.base, self.root))

    def test_renaming_code_to_documentation_does_not_hide_deleted_code(self):
        (self.root / 'docs').mkdir()
        self.git('mv', 'app/source.kt', 'docs/example.md')
        self.commit('move')
        self.assertTrue(plan.requires_android('push', self.base, self.root))

    def test_unusual_filenames_do_not_hide_code_changes(self):
        self.write('docs/中文 notes\ncontinued.md', 'Documentation\n')
        self.commit('docs with unusual name')
        self.assertFalse(plan.requires_android('push', self.base, self.root))
        self.write('app/source\nREADME.md', 'Packaged app content\n')
        self.commit('app resource')
        self.assertTrue(plan.requires_android('push', self.base, self.root))

    def test_unknown_build_workflow_and_app_assets_are_not_documentation(self):
        for path in ('app/src/main/assets/README.md', '.github/workflows/debug.yml',
                     'app/build.gradle.kts', 'gradle.properties', 'docs/build.py',
                     'tools/ci/README.md', 'assets/unknown.json'):
            with self.subTest(path=path):
                self.assertFalse(plan.documentation_path(path))

    def test_manual_missing_invalid_zero_and_empty_diff_keep_all_checks(self):
        for event, base in (('workflow_dispatch', self.base), ('unknown', self.base),
                            ('push', ''), ('push', '--not-a-sha'), ('push', '0' * 40),
                            ('push', '1' * 40), ('push', self.base)):
            with self.subTest(event=event, base=base):
                self.assertTrue(plan.requires_android(event, base, self.root))


class SdkCheckTest(unittest.TestCase):
    def test_sdkmanager_is_called_only_for_missing_components(self):
        for workflow in ('debug.yml', 'release.yml'):
            script = workflow_run_block(workflow, 'Ensure Android SDK')
            for platform, tools in ((True, True), (False, True), (True, False), (False, False)):
                with self.subTest(workflow=workflow, platform=platform, tools=tools):
                    with tempfile.TemporaryDirectory(prefix='peekturn-sdk-test-') as directory:
                        root = Path(directory)
                        sdk = root / 'android sdk'
                        manager = sdk / 'cmdline-tools/latest/bin/sdkmanager'
                        manager.parent.mkdir(parents=True)
                        manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >> "$SDK_CALLS"\n')
                        manager.chmod(0o755)
                        if platform:
                            (sdk / 'platforms/android-35').mkdir(parents=True)
                        if tools:
                            signer = sdk / 'build-tools/35.0.0/apksigner'
                            signer.parent.mkdir(parents=True)
                            signer.touch()
                            signer.chmod(0o755)
                        calls = root / 'calls'
                        env = {**os.environ, 'ANDROID_HOME': str(sdk),
                               'GITHUB_ENV': str(root / 'env'), 'SDK_CALLS': str(calls)}
                        result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script],
                                                env=env, capture_output=True, text=True)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        expected = ([] if platform else ['platforms;android-35'])
                        expected += ([] if tools else ['build-tools;35.0.0'])
                        self.assertEqual(calls.read_text().splitlines() if calls.exists() else [], expected)


class ReleaseTagTest(GitFixture):
    def check_tag(self, tag):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c',
                               workflow_run_block('release.yml', 'Verify release tag')],
                              cwd=self.root, env={**os.environ, 'TAG': tag},
                              capture_output=True, text=True)

    def test_annotated_and_lightweight_tags_at_head_are_accepted(self):
        self.git('tag', 'v2.5.1')
        self.git('tag', '-a', 'v2.5.2', '-m', 'annotated')
        for tag in ('v2.5.1', 'v2.5.2'):
            result = self.check_tag(tag)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_branch_names_missing_tags_and_wrong_commits_are_rejected(self):
        self.git('branch', 'v9.0.0')
        for tag in ('', 'main', 'v9.0.0', 'v2.5.99', 'v2.5.1 bad'):
            with self.subTest(tag=tag):
                self.assertNotEqual(self.check_tag(tag).returncode, 0)
        self.git('tag', 'v2.5.1')
        self.write('README.md', 'Newer commit with same app version\n')
        self.commit('changed')
        self.assertNotEqual(self.check_tag('v2.5.1').returncode, 0)


if __name__ == '__main__':
    unittest.main()
