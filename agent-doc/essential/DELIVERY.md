# Validation and delivery

Read this when testing or packaging app changes. Use JDK 17 and Android SDK platform 35; Gradle and scripts own exact build configuration. [RELEASE.md](../../RELEASE.md) owns signing setup and public release procedures.

## Targeted tests

Run only the tests needed for the change, using actual class names from `app/src/test` and `app/src/androidTest`:

```sh
./gradlew :app:testDebugUnitTest --tests 'qualified.TestClass'
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=qualified.DeviceTestClass
```

Debug instrumentation supports acceptance; it does not replace using the signed production app through its real UI.

- Video opening, source or mode changes require `VideoPlaybackLiveTest` with `-Pandroid.testInstrumentationRunnerArguments.videoLiveId=PUBLIC_VIDEO_ID`. It must reach Media3 READY, advance playback and switch Read/Watch. A skipped test, metadata-only result or network failure is blocked acceptance.
- For intermittent buffering, use `VideoPlaybackContinuityLiveTest` with `videoContinuityId`, `continuityMode`, `continuityHeight`, `continuityStartMs` and `continuitySeconds`. Check sustained clock advancement, rebuffering, buffer depth and dropped frames; a short opening test is insufficient.
- For downloads, complete a real download, force-stop, disable Wi-Fi/mobile networking, then cold-open and verify audio/video playback, seeking, Read/Watch and progress. Test setup and instrumentation alone do not establish offline UI acceptance.
- Live model tests use macOS Keychain service `app.peekturn.reader.model-tests`, account `provider`. Read the selected credential into memory only; never print it or substitute another account's credentials.

## Local production APK

After substantial features or refactors:

```sh
./tools/local-release.sh
```

This accepts uncommitted work, builds with the existing release key, verifies signer/package/non-debuggable status and collects a checksum and receipt. It does not run tests or lint, install, change versions or publish. Run chosen tests separately; do not edit build inputs during packaging.

Use the fresh directory printed under `app/build/outputs/delivery/`, not an older APK. Verify its checksum and signature with `shasum -a 256 -c` and the SDK's `apksigner verify --verbose --print-certs`.

```sh
adb devices -l
adb -s DEVICE_SERIAL install -r /absolute/path/to/PACKAGE.apk
adb -s DEVICE_SERIAL shell am start -W -n app.peekturn.reader/app.peekturn.reader.MainActivity
```

Production is `app.peekturn.reader`; debug is `app.peekturn.reader.debug` with separate data. Never uninstall or clear data to bypass signature/downgrade failures. Exercise the changed journey, not just launch. Report the APK path/version, installation result, visible and audible results, and unavailable checks.

## Device and release limits

- After changing emulator graphics backends, cold boot with `-no-snapshot`; `-no-snapshot-save` can still load an incompatible snapshot. Device/emulator crashes are not passed tests.
- Public releases use `tools/release.sh` and its clean-worktree/test/lint gates. Do not weaken them for local iteration.
- Commit, push, tag and publish require explicit authorization. A `v*` tag push can publish automatically. After authorized publishing, verify remote SHAs, CI results and downloadable assets using [RELEASE.md](../../RELEASE.md).
