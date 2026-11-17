# Installing MeshLink on Android

## Current CI APK identity

The current debug build uses:

- Application ID: `com.aistudio.meshlink.rx7kqm`
- Minimum Android version: Android 7.0 (API 24)
- Target SDK: API 36
- Version: `1.1 (versionCode 2)`
- APK type: single debug APK (`app-debug.apk`)

The application ID is intentionally documented. Do not change it casually: changing it makes Android treat the build as a different app and it will not update an existing installation.

## Why Android may say "App not installed"

The file manager's generic message does not identify the cause. Common causes include:

1. **Signing certificate mismatch:** Android will not update an installed app if the new APK is signed by a different key. The CI workflow can now use a stable owner-provided debug key through the `MESHLINK_DEBUG_KEYSTORE_BASE64` GitHub Actions secret. Without that secret, CI creates a temporary key and labels the artifact as fresh-install only.
2. **Existing app signed by another key:** This can happen when switching between a local build, an AI Studio build, and a CI build. Preserve the original signing key to update without uninstalling.
3. **Insufficient storage or damaged download:** Re-download the artifact and check the SHA-256 value in `build-info.txt`.
4. **Package/version conflict:** Keep the same application ID and use a higher versionCode for future public releases.

## Recommended fix: keep one signing key

If you own the stable debug keystore used for the currently installed MeshLink app, encode that exact file as Base64 and add it to GitHub:

- Repository: Settings → Secrets and variables → Actions → New repository secret
- Secret name: `MESHLINK_DEBUG_KEYSTORE_BASE64`
- Secret value: Base64 contents of the keystore file

The current Gradle debug signing configuration expects:
- Keystore password: `android`
- Key alias: `androiddebugkey`
- Key password: `android`

The keystore must contain that alias and use those passwords. Never commit a private signing key to the repository. Do not use a debug key for a public production release.

Windows PowerShell example to create the Base64 value (replace the path with your own existing keystore):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\debug.keystore"))
```

Use the existing keystore that signed the installed app whenever possible. Creating a new keystore will not make it compatible with an app signed by the old key.

## Safest way to diagnose the exact failure

Enable USB debugging, connect the phone to a computer, and run:

```bash
adb devices
adb install -r app-debug.apk
```

ADB prints a useful reason such as `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, `INSTALL_FAILED_VERSION_DOWNGRADE`, `INSTALL_FAILED_NO_MATCHING_ABIS`, or `INSTALL_FAILED_INSUFFICIENT_STORAGE`.

If ADB is unavailable, open Android Settings → Apps → see all apps → MeshLink and check whether an older MeshLink is already installed. **Uninstalling it can erase its local messages, trusted peers, keys and settings. Back up anything important first.** Only uninstall as a temporary test if you accept that data loss. Then install the new APK again.

## Artifact verification

Download both `app-debug.apk` and `build-info.txt` from the same GitHub Actions artifact. Confirm the APK SHA-256 against the `sha256` entry. Do not rename or modify the APK before checking it.

A successful CI build proves that the project compiles and unit tests pass. It does not prove that a specific phone accepted the APK or that wireless transports work on physical devices.
