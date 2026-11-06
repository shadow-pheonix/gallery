# Local Android build

This checkout adds **Settings → Diagnostics → Export diagnostics**. The action saves a UTF-8
`edge-gallery-diagnostics-<UTC timestamp>-<suffix>.txt` file directly to the device's public
Downloads folder. A message shows the saved filename or a save error.

The report includes app/device versions, RAM/heap/storage information, model download status,
and up to 2,000 recent log lines from the current app process (capped at 512 KiB). If Android
restricts log access, the report records that logs are unavailable. It does not request
additional storage permissions. Credential redaction is best effort; review logs before sharing.

Build from `Android/src` using JDK 17 and the Android SDK:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain --max-workers=2
```

On a machine with limited RAM, run the compiler in the Gradle process and use one worker:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain --max-workers=1 --no-daemon \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

The APK is generated at `Android/src/app/build/outputs/apk/debug/app-debug.apk` and is signed
with the local Android debug key. It requires Android 12 (API 31) or later. It cannot update
an installed official APK signed with Google's key.

The upstream Hugging Face OAuth configuration contains placeholders (see `DEVELOPMENT.md`).
Configure your own OAuth application for browser sign-in, or enter a Hugging Face access token
in Settings to download gated models. This build does not include a Hugging Face credential.
