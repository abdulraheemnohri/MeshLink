# MeshLink — Decentralized Offline Android Mesh Communication Network

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-orange.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![Database](https://img.shields.io/badge/Database-Room%20%2F%20SQLite-blueviolet.svg)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**MeshLink** is a decentralized, zero-server, offline Android mesh communication network designed to allow nearby mobile devices to communicate, exchange end-to-end encrypted messages, share files, stream push-to-talk voice, and bridge local networks without relying on mobile data, cellular SIM cards, Wi-Fi routers, or internet access.

---

## 🚀 Key Features

- **No Internet or SIM Required**: Operates entirely over local wireless hardware.
- **Hybrid Transport Layer**: Automatic capability detection and fallback across **Bluetooth LE (BLE)**, **Wi-Fi Direct (P2P)**, **Wi-Fi Aware (NAN)**, and **Local Wi-Fi LAN / Hotspot**.
- **End-to-End Encryption (E2EE)**: Android KeyStore-backed ECDSA P-256 identities, ECDH key exchange, HKDF-SHA256, and AES-256-GCM authenticated encryption. Relay nodes transport ciphertext without plaintext access.
- **Multi-Hop DTN Routing**: Store-and-forward delay-tolerant networking with Direct routing, Epidemic dissemination, and Spray-and-Wait TTL-bounded forwarding.
- **Network Bridge**: User-configurable cross-cluster bridging connecting two isolated local mesh networks (e.g., BLE ⇄ Local LAN).
- **Community Groups**: Persistent local chat groups for communities, events, and rescue operations with shareable `meshlink://` QR/URI invitations and AES-256 group key rotation.
- **Emergency Mode**: Priority-3 high-priority SOS broadcasts with offline GPS coordinate attachment and best-effort delivery acknowledgment tracking.
- **Offline File Sharing & Voice PTT**: Chunk-based file transfers with SHA-256 integrity checks and push-to-talk audio clips.
- **Biometric Vault Protection**: Hardware-backed biometric and device screen lock confirmation on app launch.

---

## 📱 Application Screens

1. **Onboarding**: Introduction and permission requests.
2. **Dashboard (`Home`)**: Network overview, active relay toggle, and quick actions.
3. **Discover**: Nearby Bluetooth LE, Wi-Fi Direct, and LAN peer scanning and QR/URI pairing.
4. **Connections**: Active and historical peer links with trust verification and blocking controls.
5. **Chats (Direct & Groups)**: E2EE direct messages and local community group channels.
6. **File Sharing**: Select and transfer photos, documents, and ZIP files with resumable chunk queues.
7. **Network Map**: Interactive radar topology showing live nodes, transport links, and bridge status.
8. **Voice PTT**: Push-to-talk and voice message recording and playback.
9. **Location**: Offline GPS coordinate sharing and relative vector computation.
10. **Transfer Manager**: File queue status, pause, resume, and hash verification.
11. **Device Identity**: QR code identity sharing and fingerprint inspection.
12. **Security**: Encryption status, privacy manifest, and app unlock settings.
13. **Routing**: DTN queue statistics, TTL limits, and routing strategy selection.
14. **Diagnostics**: Hardware capabilities, loopback self-tests, and network event logs.
15. **Settings**: Theme selection (Dark/Light/System), transport toggles, and SOS templates.
16. **Emergency Mode**: SOS broadcast console and delivery acknowledgment tracking.

---

## 🛠️ Technology Stack

- **Language**: Kotlin 2.2
- **UI Framework**: Jetpack Compose & Material 3
- **Architecture**: MVVM + Clean Architecture principles
- **Local Database**: Room / SQLite (Encrypted offline tables)
- **Navigation**: Navigation Compose with type-safe route routing
- **Concurrency**: Kotlin Coroutines & Flows (`StateFlow`, `SharedFlow`)

---

## 📦 Building & Running

1. Clone the repository:
   ```bash
   git clone https://github.com/abdulraheemnohri/MeshLink.git
   cd MeshLink
   ```
2. Compile and assemble debug APK:
   ```bash
   gradle assembleDebug
   ```
3. Locate the built APK in `apk/MeshLink-debug.apk` or `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

Distributed under the **MIT License**. See [LICENSE](LICENSE) for more information.
