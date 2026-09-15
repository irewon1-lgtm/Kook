# KR4 native app auto-update

## What updates automatically

- Stock snapshot JSON: existing `SnapshotAutoUpdater` path.
- Native Android APK/UI/code: `AppAutoUpdater` + signed GitHub Release path.

## Runtime flow

1. The app checks `https://github.com/irewon1-lgtm/Kook/releases/latest/download/update.json`.
2. A higher `versionCode` is required.
3. The APK is downloaded to app-private cache automatically.
4. SHA-256 from `update.json` must match exactly.
5. APK package name must remain `com.krstock.v3`.
6. APK versionCode must match the manifest.
7. APK signing certificate must match the currently installed KR4 app.
8. Only then does the app open Android's trusted package installer.

Android does not permit a normal sideloaded application to silently replace itself. The final Android install confirmation is therefore intentionally preserved.

## Stable signing requirement

The release workflow reads these GitHub Actions repository secrets:

- `KR4_KEYSTORE_BASE64`
- `KR4_KEYSTORE_PASSWORD`
- `KR4_KEY_ALIAS`
- `KR4_KEY_PASSWORD`

The private signing key must never be committed to this public repository.

## Release behavior

After Extreme CleanPass succeeds on `main`, the workflow publishes a signed release only when app/workflow code changed. Data-only commits do not force users to reinstall the application.

CI assigns a monotonically increasing versionCode (`10000 + workflow run number`) and publishes fixed asset names:

- `KR4.apk`
- `update.json`
- `APK_SIGNING_CERT.txt`

The fixed `releases/latest/download/...` URLs let installed applications discover future releases without changing app code.

## Bootstrap migration

Older KR4 builds were produced as CI debug APKs without a persistent private signing key. Android will not allow a different signing identity to update such an installation in place.

Therefore the first stable-signed KR4 installation is a one-time migration:

1. Back up anything local if local-only state is later added. Current stock snapshot data is recoverable from the validated remote/bundled sources.
2. Uninstall the old debug-signed KR4 build.
3. Install the first stable-signed KR4 APK.
4. From that point onward, future stable-signed releases update in place through the automatic updater.
