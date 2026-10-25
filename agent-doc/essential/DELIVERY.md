# Build and delivery

[README: Build](../../README.md#build) owns toolchain setup. [RELEASE.md](../../RELEASE.md) owns signing, public releases and Play delivery. Scripts and manifests are the source of truth for exact commands and versions.

## Local builds

- JDK 17; the Gradle wrapper selects it on macOS when `JAVA_HOME` is unset. SDK lookup: ignored `local.properties` (`sdk.dir`), then `ANDROID_HOME`, then `ANDROID_SDK_ROOT`.
- `./gradlew :app:assembleDebug` builds an APK without running the test suite. Run affected tests separately with `:app:testDebugUnitTest --tests 'qualified.TestClass'`.
- [`tools/package-apk.sh`](../../tools/package-apk.sh) runs all debug unit tests, debug lint and debug assembly, then collects a universal test APK, checksum and receipt. It accepts uncommitted changes and records them in the receipt.
- [`tools/release.sh`](../../tools/release.sh) requires a clean worktree, runs debug unit tests and release lint, builds signed APK/AAB, verifies signatures and collects checksums plus a receipt. It needs Python 3, Git, macOS Keychain access and Android signing tools.
- [`tools/local-release.sh`](../../tools/local-release.sh) is the default for fast local production APKs. It accepts uncommitted work, reads the existing signing key/password configuration, runs only incremental release assembly (excluding automatic lint-vital tasks), verifies the expected signer/package/non-debuggable flag, and collects the APK/checksum/receipt. It does not run tests, lint, AAB assembly, install, version changes or Git writes. The receipt explicitly marks tests/lint/runtime checks `not_run`; do not infer results from old reports.
- Select target tests separately for the actual change, for example `./gradlew :app:testDebugUnitTest --tests 'app.peekturn.reader.video.VideoMediaCacheTest.leastRecentFragmentsAreEvictedWithinTheByteBudget'`. A local signed package is not a reason to broaden that scope. Keep the public `release.sh` clean-tree/test/lint gate unchanged. A debug package is not a substitute for a production APK.
- Real model smoke tests use the provider JSON stored in macOS Keychain under service `app.peekturn.reader.model-tests`, account `provider` (`endpoint` and `apiKey`). Read it into memory only; never echo it, add it to the repository, or substitute the coding assistant's own credentials. Query that provider's model list and prefer an available DeepSeek or Doubao model. The Android app still uses its normal encrypted Settings profile.

## Signing and identity

- Production: `app.peekturn.reader`, non-debuggable. Debug: `app.peekturn.reader.debug`, separate books/settings, can coexist with production.
- The upload keystore is `~/.config/peekturn/peekturn-upload.jks`, alias `peekturn-upload`. Its password is in macOS Keychain, service `com.peekturn.reader.upload-key`, account `upload`. Neither belongs in the repository or logs.
- Gradle signing uses `PEEKTURN_STORE_FILE`, `PEEKTURN_STORE_PASSWORD`, `PEEKTURN_KEY_ALIAS` and `PEEKTURN_KEY_PASSWORD`. The release script reads the store password from Keychain; CI supplies secrets.
- Trusted `main` debug CI restores the dedicated debug key. Local builds use `~/.android/debug.keystore`; PR builds may use temporary keys. A signature conflict is not permission to uninstall the old app.

## Collect and verify

Scripts print a fresh directory under `app/build/outputs/delivery/`. Use that invocation's output, not an older APK found by name. `output-metadata.json` owns package/version/output identity. Receipts distinguish dirty input, completed checks and runtime verification; a build alone does not establish device acceptance. Do not edit inputs while packaging.

From the collected directory, verify `shasum -a 256 -c ./*.apk.sha256` and `apksigner verify --verbose --print-certs PACKAGE.apk` using the SDK build-tools binary. CI and local APK hashes can differ; verify each artifact against its own checksum and expected signer.

```sh
adb devices -l
adb -s DEVICE_SERIAL install -r /absolute/path/to/PACKAGE.apk
adb -s DEVICE_SERIAL shell am start -W -n app.peekturn.reader/app.peekturn.reader.MainActivity
```

For debug, the launch component is `app.peekturn.reader.debug/app.peekturn.reader.MainActivity`. Never clear app data or uninstall to bypass signature/downgrade failures: that removes private books and settings. Device checks should exercise the changed interaction, not just launch.

## Remote delivery

The tag must be `v<versionName>` from `app/build.gradle.kts`; published version codes must increase. Pushing a `v*` tag triggers the existing release workflow, which runs checks, signs the APK and publishes it with its checksum to GitHub Releases. Main pushes also run debug CI. Public APK delivery and Play AAB delivery are separate; CI publishes only the APK.

After an authorized push, verify branch/tag SHAs, CI conclusions and the actual downloadable assets. The script and workflow contain the signing-secret names; [RELEASE.md](../../RELEASE.md) covers the publication procedure. No release is implied by a local build or tag alone.
