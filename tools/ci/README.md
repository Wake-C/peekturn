# CI boundaries

`debug.yml` keeps the same `debug` check for every PR and main push. The small
Python checks always run. `plan.py` omits Android work only when the complete
push range or PR diff contains documentation and README media from its explicit
allowlist. Unknown paths, an unavailable diff, an empty diff, and manual runs
request the full Android checks. Renames are checked as a deletion and addition.
The summary explicitly reports when Android checks were not run.

Only superseded PR runs are cancelled. Main and manual runs have unique
concurrency groups so that queued commits are not replaced.

Both workflows restore Gradle dependencies and task outputs using GitHub's
cache action. Keys prefer the same wrapper and build inputs, then fall back to
the same wrapper across version/dependency changes. Gradle validates the inputs
of each task; a restored cache never bypasses the tests/lint/build command.
Only successful main pushes save cache entries. PR and Release runs are
read-only. Keystores, project outputs, configuration cache, and Gradle credential
configuration files are outside the cached paths. The first run is cold; include cache restore/save
time when comparing later builds.

Release runs check out an explicit `refs/tags/...`, including manual retries.
They still run the existing unit tests, release lint, signing, version and
signature checks before uploading the APK and checksum. Never push a version
tag or republish an asset just to benchmark CI.

Fast local checks (no Android SDK required):

```sh
python3 -m unittest discover -s tools/tests -p 'test_*.py'
actionlint
```
