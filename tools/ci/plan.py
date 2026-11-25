"""Keep the Debug check, but omit Android work for verified documentation-only diffs."""

import os
from pathlib import Path, PurePosixPath
import re
import subprocess


def documentation_path(path):
    name = PurePosixPath(path)
    if path in {'AGENTS.md', 'CONTEXT.md', 'RELEASE.md', 'THIRD_PARTY_NOTICES.md', 'LICENSE'}:
        return True
    if '/' not in path and path.startswith('README') and name.suffix == '.md':
        return True
    if path.startswith(('agent-doc/', 'docs/', 'release-notes/')) and name.suffix == '.md':
        return True
    return (path.startswith(('assets/images/', 'assets/video/'))
            and name.suffix.lower() in {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.mp4'})


def requires_android(event, base, cwd='.'):
    # Manual runs and incomplete event/diff data must never skip validation.
    if event not in {'push', 'pull_request'} or not re.fullmatch(r'[0-9a-f]{40}', base or ''):
        return True
    if base == '0' * 40:
        return True
    try:
        options = dict(cwd=cwd, capture_output=True, check=True, timeout=30)
        try:
            subprocess.run(['git', 'cat-file', '-e', f'{base}^{{commit}}'], **options)
        except subprocess.CalledProcessError:
            subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', base], **options)
        result = subprocess.run(
            ['git', 'diff', '--no-renames', '--name-only', '-z', base, 'HEAD', '--'], **options)
    except (OSError, subprocess.SubprocessError):
        print('Diff unavailable; keeping all Android checks.')
        return True
    # NUL separators handle arbitrary filenames. --no-renames checks both sides of a move.
    paths = result.stdout.decode('utf-8', errors='surrogateescape').split('\0')
    paths = [path for path in paths if path]
    return not paths or any(not documentation_path(path) for path in paths)


if __name__ == '__main__':
    required = requires_android(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('BASE_SHA', ''))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'android={str(required).lower()}\n')
    summary = ('Full Android tests, lint and APK build are required.' if required else
               'Documentation-only change: Android tests, lint and APK build were not run.')
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as output:
            output.write(summary + '\n')
