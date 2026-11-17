# MeshLink — Decentralized Offline Android Mesh Communication Network

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-orange.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

MeshLink is an Android-first, offline-first peer-to-peer communication project. Its goal is to support nearby messaging and file exchange over compatible local wireless transports, with delay-tolerant forwarding when relay nodes are available.

> **Project status:** Source-code features are not automatically proven network capabilities. Real-device transport tests and an independent security review are required before describing MeshLink as production-ready. See [implementation and release status](docs/IMPLEMENTATION_STATUS.md) and the [test plan](docs/TEST_PLAN.md).

## Design principles

- No central server is required for local peer communication.
- Internet access is not required for supported local transports. Android's `INTERNET` permission may still be used for local sockets; it does not itself mean MeshLink requires public internet.
- Transport capabilities are detected at runtime; unsupported radios are disabled gracefully.
- Relays forward opaque encrypted envelopes where end-to-end encryption is implemented and verified.
- Store-and-forward is best-effort. Delivery depends on peers being available, powered, permitted to run and willing to relay.
- Emergency messages are not guaranteed to reach recipients and must not replace emergency services.

## Intended capabilities

- Nearby peer discovery and pairing.
- Direct messaging and durable pending-message queues.
- Bluetooth LE, Bluetooth Classic RFCOMM, Google Nearby Connections (Google Play services required), Wi-Fi Direct, Wi-Fi Aware and local-LAN transport adapters where supported.
- Bounded packet framing and chunked file transfer.
- Store-and-forward and hop-limited routing.
- Peer identity verification and encrypted local storage.
- Same-network WLAN/LAN and compatible hotspot networking.
- Automatic best-available transport fallback across registered adapters.
- NFC / QR identity bootstrap (not general-purpose continuous data links).
- A per-feature Android Permission Manager for camera, Bluetooth/Nearby Devices, Nearby Wi-Fi, location, microphone and notifications.
- Diagnostics and transport capability reporting.

The implementation status of each capability must be verified using the acceptance criteria in `docs/IMPLEMENTATION_STATUS.md`.

## Android compatibility

Transport support depends on Android version, device chipset, OEM behavior, runtime permissions and current system policy. BLE, Wi-Fi Direct and Wi-Fi Aware are not universally available. Background discovery and long-running connections are subject to Android battery and foreground-service restrictions.

MeshLink does not provide unlimited range. Multi-hop communication requires other compatible MeshLink nodes to be nearby and actively relaying.

## Technology

- Kotlin and Jetpack Compose
- Android Bluetooth, Wi-Fi P2P/Aware and optional Google Nearby Connections APIs
- Kotlin Coroutines and Flow
- Room / SQLite
- Gradle

## Build

Install JDK 21. The repository includes the official Gradle 9.3.1 Wrapper, so a separate Gradle installation is not required.

```bash
./gradlew --version

# Generate the local debug key expected by app/build.gradle.kts (do not use for release signing).
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

./gradlew --no-daemon assembleDebug
./gradlew --no-daemon testDebugUnitTest
```

The debug APK is normally generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows PowerShell, use ` .\\gradlew.bat ` (without the surrounding spaces) in place of `./gradlew` for the same tasks.

GitHub Actions verifies the wrapper, compiles the debug APK and runs unit tests. Check the workflow result for the exact commit before treating a change as build-verified.

## Development documentation

- [Networking modes, supported transports and selection policy](docs/NETWORKING_MATRIX.md)
- [Architecture and implementation status](docs/IMPLEMENTATION_STATUS.md)
- [Automated and physical-device test plan](docs/TEST_PLAN.md)
- [Android APK installation and signing troubleshooting](docs/ANDROID_INSTALLATION.md)
- [Full project audit and remaining release gates](docs/PROJECT_AUDIT.md)
- [Packet-level security review and encryption matrix](docs/SECURITY_REVIEW.md)
- [Multi-device acceptance plan](docs/MULTI_DEVICE_ACCEPTANCE_TESTS.md)
- [Physical transport acceptance results](docs/TRANSPORT_ACCEPTANCE_RESULTS.md)
- [Signed Android release and signing-key management](docs/ANDROID_RELEASE.md)
- [Routing invariants and multi-hop behavior](docs/ROUTING.md)
- [Security policy](SECURITY.md)

## Contributing

Please include tests for protocol, routing, security and persistence changes. Do not submit cryptographic changes based only on custom algorithms or unverified claims. Prefer established, reviewed protocols and document compatibility implications.

## License

Distributed under the MIT License. See [LICENSE](LICENSE).
