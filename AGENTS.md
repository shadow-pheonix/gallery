# Development and publication workflow

- Make changes in the local checkout, review them, then commit and push to the user's fork at
  `https://github.com/shadow-pheonix/gallery`.
- Do not build APKs or run Android Gradle builds on this development server. Use the GitHub
  Actions workflow `.github/workflows/build_android.yaml` for builds and unit tests.
- After pushing completed Android changes to `main`, monitor the Actions run and verify that
  the APK and checksum are available on the GitHub Releases page. Fix CI failures locally and
  push the fixes; do not work around failures by building an APK on this server.
- Every published APK must retain the original signing certificate so users can update in
  place. Preserve the encrypted `ANDROID_DEBUG_KEYSTORE_BASE64` repository secret and the
  workflow's certificate checks. Never commit a keystore, its base64 encoding, or credentials.
- Local read-only checks, source review, workflow validation, and verification of an already
  built APK are allowed. Keep Google's source repository configured as `upstream` and the
  user's fork as `origin`.
