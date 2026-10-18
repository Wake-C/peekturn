# Release

This project ships a signed release APK for direct distribution and a signed AAB for Google Play. Debug builds are for development only.

## One-time upload key

A 4096-bit RSA upload key already exists outside the repository:

```text
~/.config/peekturn/peekturn-upload.jks
```

The password is stored in macOS Keychain under `com.peekturn.reader.upload-key`, account `upload`. Do not copy the keystore or password into this repository.

Back up both the keystore and the password somewhere safe. Losing the upload key can block updates unless Google Play lets you reset it. With Play App Signing, Google holds the app signing key; this file is only the upload key.

## Build a release

The worktree must be clean. The release script reads the password from Keychain, builds a non-debuggable release, runs unit tests and release lint, verifies both signatures, and writes checksums plus a receipt.

```sh
./tools/release.sh
```

Outputs:

```text
app/build/outputs/delivery/release.*/peekturn-1.0.0-1-release.apk
app/build/outputs/delivery/release.*/peekturn-1.0.0-1-release.aab
app/build/outputs/delivery/release.*/receipt.json
```

Install the release APK on a device and check the first-run, import, reading, explanation, pronunciation and narration paths before publishing. The receipt records runtime verification as `not_run`; a build is not a device acceptance result.

## GitHub release

The repository release page is the download page. Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds a signed release APK on CI and attaches the APK and its `.sha256` to a GitHub Release.

1. Confirm the release APK installs, launches and opens a real EPUB. A build is not a device acceptance result.
2. Bump `versionName` and `versionCode` in `app/build.gradle.kts`, then commit and confirm `git status` is clean.
3. Create a tag that matches `versionName` and push it:

```sh
git tag -a v1.0.0 -m "Peekturn 1.0.0"
git push origin main --follow-tags
```

4. Review the generated release notes and replace them with the user-facing changes. Do not describe tests that were not run.

The workflow fails when the tag and `versionName` disagree, and when the signing secrets are absent. It never publishes an unsigned APK.

CI signs with the same upload keystore as `tools/release.sh`, supplied as repository secrets:

| Secret | Value |
| --- | --- |
| `PEEKTURN_KEYSTORE_BASE64` | `base64 -i ~/.config/peekturn/peekturn-upload.jks`, newlines removed |
| `PEEKTURN_STORE_PASSWORD` | keystore password from Keychain |
| `PEEKTURN_KEY_ALIAS` | `peekturn-upload` |
| `PEEKTURN_KEY_PASSWORD` | key password |

CI builds the APK only. Run `tools/release.sh` locally when you need the AAB for Play.

A private repository publishes private releases: only the owner and collaborators can download them. Make the repository public before the download link is meant to work for anyone else.

## Google Play first release

The package name `app.peekturn.reader` is permanent after the first upload. Check that it is available in Play Console before creating the app.

1. Create the app in Play Console and enable Play App Signing.
2. Upload `peekturn-1.0.0-1-release.aab`.
3. Complete the store listing, screenshots, short and full description, app icon and feature graphic.
4. Publish a privacy policy at a public HTTPS URL. The repository README is not a privacy policy page.
5. Complete Data safety, content rating, target audience and ads declarations.
6. Complete the testing requirement that Play Console shows for your account type. New personal developer accounts may need a closed test before production access.
7. Roll out to internal testing first, then closed testing, then production.

Do not upload a debug APK or a release APK signed with a different key.

## Versioning

- `versionName` is the public semantic version: `1.0.0`, `1.0.1`, `1.1.0`.
- `versionCode` is an integer that must increase for every uploaded build: `1`, `2`, `3`.
- Change both in `app/build.gradle.kts` before building a release.
- Never reuse a version code after an upload.

## If the upload key is lost

If Play App Signing is enabled, use the Play Console upload-key reset flow and create a new upload key. Existing installations continue to trust the app signing key held by Google. Without Play App Signing, losing the signing key prevents updates to the installed app.
