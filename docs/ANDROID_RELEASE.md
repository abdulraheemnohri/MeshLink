# MeshLink Android release and signing

## Current build setup

- Android application ID: `com.aistudio.meshlink.rx7kqm`
- Current source version: `1.1` / versionCode `2`
- Release signing uses the Android Gradle `release` signing config.
- The release workflow builds only when a tag matching `v*` is pushed.
- The release workflow verifies the APK signature with `apksigner`, extracts actual package/version metadata from the APK, records SHA-256 and publishes the APK plus verification evidence.
- Android CI continues to build a debug APK and run JVM tests.

## Required GitHub Actions secrets

Configure these repository secrets before creating a release tag:

| Secret | Purpose |
|---|---|
| `MESHLINK_RELEASE_KEYSTORE_BASE64` | Base64-encoded upload/release JKS file |
| `MESHLINK_RELEASE_STORE_PASSWORD` | Keystore password |
| `MESHLINK_RELEASE_KEY_PASSWORD` | Signing key password |
| `MESHLINK_RELEASE_KEY_ALIAS` | Alias inside the keystore; currently commonly `upload` |

Never commit a keystore, signing password, private key, or base64 keystore value. The repository ignores common Android signing file extensions.

## Release procedure

1. Confirm Android CI passes on the exact source commit.
2. Confirm the Room schema snapshot is reviewed and committed.
3. Run the physical acceptance plan and attach its evidence before making production claims.
4. Increase `versionCode` and set `versionName` in `app/build.gradle.kts`.
5. Confirm the tag is exactly `v<versionName>`, for example `v1.2`.
6. Configure the four release secrets above.
7. Push the version tag. The signed release workflow builds `assembleRelease`, verifies the signature, writes build metadata and publishes the APK and verification evidence.
8. Download the release APK and verify its SHA-256 and signing certificate fingerprint before distribution.
9. Install over an existing build only if its application ID and signing certificate match.

## Upgrade and signing warnings

- Android requires the same application ID and signing certificate to update an installed app.
- A release-signed APK will not update an installation signed with a different debug key. Debug and production signing identities are distinct unless deliberately configured otherwise.
- If the release signing key is lost, future upgrades under the same package ID may be impossible. Keep encrypted offline backups of the keystore and credentials.
- Do not publish a release until the certificate fingerprint matches the intended existing production signing identity.
- The GitHub release workflow is configured but has not yet been exercised with production signing secrets or a real version tag.

## Gradle Wrapper status

The repository contains `gradle/wrapper/gradle-wrapper.properties`, but it does not currently contain the wrapper JAR or `gradlew` / `gradlew.bat` launch scripts. CI installs Gradle 9.3.1 directly and therefore does not depend on a wrapper. Do not run `./gradlew` until the official Gradle 9.3.1 wrapper JAR and scripts are added and verified.
