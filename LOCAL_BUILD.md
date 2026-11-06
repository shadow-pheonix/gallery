# Android builds and releases

Make changes locally, commit them, and push them to `shadow-pheonix/gallery`.
Do not build APKs on the development server. Builds and unit tests run in GitHub Actions.

The **Build and release Android APK** workflow runs on Android/workflow changes pushed to
`main`, and can be started manually from the Actions tab. Pull requests run builds and tests
without access to the signing secret and without publishing releases.

After tests pass, Actions verifies the APK signature and publishes the APK and SHA-256 checksum
on the [Releases page](https://github.com/shadow-pheonix/gallery/releases). Each CI build has an
increasing Android version code and a version name containing its workflow build number.

To start a build manually:

```sh
gh workflow run build_android.yaml --repo shadow-pheonix/gallery --ref main
```

## Stable signing

All published APKs use the signing identity of the original custom APK, allowing updates in
place. The keystore is stored only in the encrypted repository secret
`ANDROID_DEBUG_KEYSTORE_BASE64`. It must never be committed to Git.

The workflow checks the certificate before building and again on the completed APK. If the
secret is missing or contains a different key, the workflow fails instead of generating a
replacement key. Preserve the original keystore when moving or recreating this repository.
Gradle receives the verified keystore's exact path through `ANDROID_SIGNING_KEYSTORE`; CI does
not rely on the runner's default debug keystore location.

Public signing certificate SHA-256:

```text
f7594eae3153851372465fe8bb10e2cf840b39bc8b9a8dcd75302e8ce7502c80
```

The APK uses the release variant with this fork's existing Android debug signing key. It
requires Android 12 (API 31) or later and cannot update Google's official APK.

## Diagnostics export

**Settings → Diagnostics → Export diagnostics** saves a UTF-8
`edge-gallery-diagnostics-<UTC timestamp>-<suffix>.txt` file to the device's public Downloads
folder. The report includes device/app versions, RAM/heap/storage information, model download
status, and recent logs from the current app process. It does not currently include structured
inference timings, saved benchmark results, or thermal and native/GPU memory measurements.
Credential redaction is best effort; review logs before sharing.

The upstream Hugging Face OAuth configuration contains placeholders (see `DEVELOPMENT.md`).
Configure your own OAuth application for browser sign-in, or enter a Hugging Face access token
in Settings to download gated models.
