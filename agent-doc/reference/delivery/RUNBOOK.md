# Debug APK delivery

## GitHub Actions test builds

`.github/workflows/debug.yml` builds on pushes to `main`, PR updates and manual dispatch. It runs debug unit tests, debug lint and assembly, then uploads an APK and SHA-256 checksum to the run's **Artifacts**, retained for 14 days. Debug builds never create a Release. New runs cancel unfinished runs for the same branch or PR.

Debug uses `app.peekturn.reader.debug`, version suffix `-debug`, and the display name Peekturn Debug (憩页 Debug in Chinese). It can coexist with the production app; books and settings are separate. Existing debug installations under the production package name are not migrated or removed.

Trusted `main` builds restore the fixed test keystore from `PEEKTURN_DEBUG_KEYSTORE_BASE64`. This is a dedicated debug key, never the release key. Local debug builds use the matching `~/.android/debug.keystore` on the maintainer's machine. PR builds receive no signing secret and use a temporary debug key, so PR APKs are not guaranteed to update another test build. Manual runs on branches other than `main` also use a temporary key. Do not uninstall an app to bypass a signature conflict without considering data loss.

## Package the current checkout

```sh
./tools/package-apk.sh
```

The script runs debug unit tests, Android lint and APK assembly through the repository Gradle wrapper. It collects an APK only if all three tasks succeed. Lint success permits warnings; it does not mean warning-free output.

This workflow delivers a universal, debug-signed test APK. It does not configure release signing, produce a store release, change versions, modify application source, rebuild the bundled dictionary, provision credentials or install to a device. Use the existing bundled assets for ordinary packaging. Version changes are separate edits to the build manifest, never an automatic packaging step.

## Prerequisites

Use the [build toolchain setup](../../../README.md#build-and-run). The packaging command also requires a POSIX shell, Python 3 and Git with an existing checkout commit. Python uses only its standard library.

SDK lookup uses `sdk.dir` in ignored `local.properties`, then `ANDROID_HOME`, then `ANDROID_SDK_ROOT`. The wrapper handles Gradle distribution setup. Gradle validates the platform/build tools selected by the manifests and may download uncached dependencies. Model-provider access and an attached Android device are not packaging prerequisites.

## Outputs and provenance

Each successful invocation creates a separate directory under ignored `app/build/outputs/delivery/package.*` containing:

- `peekturn-<versionName>-<versionCode>-debug.apk`
- The matching `.apk.sha256` checksum file
- `receipt.json`

Use the directory printed by the successful invocation, not an older APK found by filename or modification time. Failed checks do not collect an existing APK; failed collection removes its incomplete directory. Previous successful deliveries remain unchanged.

The script reads package identity, version and the single universal APK path from Gradle's generated `output-metadata.json`, not a second version setting or a filename guess. It rejects split outputs and unsafe paths. The receipt records those values, the copied APK's SHA-256, UTC packaging time, Git revision, dirty-worktree flag and completed checks.

The Git revision identifies the checkout's commit. A dirty flag means the checkout had uncommitted or untracked files when packaging started; it is not a content hash of those files, and the commit alone does not identify all build inputs. Do not edit the checkout while packaging or run concurrent packaging invocations in the same checkout. Packaging does not promise byte-for-byte reproducibility across machines.

To verify the collected file on macOS:

```sh
cd /path/to/the/printed/delivery/directory
shasum -a 256 -c ./*.apk.sha256
```

On systems with `sha256sum`, use `sha256sum -c ./*.apk.sha256` instead.

## Install and launch explicitly

Use `adb` from the configured SDK's `platform-tools` directory. Select the intended device; do not uninstall an existing app or clear its data to bypass an installation error.

```sh
adb devices -l
APK=/absolute/path/to/the/collected.apk
adb -s DEVICE_SERIAL install -r "$APK"
adb -s DEVICE_SERIAL shell am start -W -n app.peekturn.reader.debug/app.peekturn.reader.MainActivity
```

An install/launch check establishes only that this APK installs and starts on that device. The receipt deliberately reports Android runtime verification as `not_run`; packaging alone does not establish feature acceptance. Record any separately executed device checks and their evidence limits in [verification history](../verification/VERIFICATION.md). Use the [product acceptance criteria](../SPEC.md#acceptance) and affected feature owner for behavior changes.

## Failure handling

- Missing JDK or SDK: fix local toolchain configuration; do not change build versions to make the command pass.
- Unit-test, lint or assembly failure: use the failing task's output and reports under `app/build/`. Do not bypass the failed gate or distribute an old APK as the new result.
- Metadata, split-output or collection failure: no delivery is published for that invocation. Investigate the build output contract before adding a different delivery format.
- Installation signature mismatch or version downgrade: stop and resolve the intended signing/version path. Removing the installed app can delete private books, settings and statistics.

Packaging orchestration regression tests run without Android tools or network access:

```sh
python3 -B -m unittest discover -s tools/tests -p 'test_*.py'
```
