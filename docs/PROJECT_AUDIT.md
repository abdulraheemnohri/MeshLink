# MeshLink project audit

Audit date: 2026-11-17  
Repository: `abdulraheemnohri/MeshLink`  
Branch: `main`

## Executive summary

MeshLink is an Android-first offline peer-to-peer messaging and file-transfer application built with Kotlin, Jetpack Compose, Room and multiple local-radio adapters. The project now has a substantial source implementation for peer discovery, identity verification, routing, encrypted private messages, group management, chunked files, diagnostics and release automation.

**Release status: development / physical-device validation required.** Source implementation and passing JVM tests do not prove radio interoperability, reliable multi-hop delivery or production security. The physical acceptance tracker remains NOT RUN.

## Active architecture

| Area | Active implementation |
|---|---|
| Android application | `app/`, Kotlin + Jetpack Compose + Material 3 |
| Identity and crypto | `core/crypto/`, P-256 ECDSA/ECDH, AES-GCM, ephemeral session handshake |
| Packet protocol | `core/protocol/MeshProtocol.kt` |
| Transport adapters | `transport/`: Local LAN, BLE, Bluetooth Classic RFCOMM, Wi-Fi Direct, Wi-Fi Aware, optional Google Nearby |
| Routing | `routing/MeshRoutingEngine.kt`, replay store, forwarding policy |
| Persistence | Room schema version 9, DAOs and `MeshRepository` |
| File transfer | Chunked transfer manager, ACKs, integrity verification, resume/export |
| Background | Foreground mesh relay service |
| UI | Compose screens, navigation, theme, settings and diagnostics |
| Support | Local bug report form and GitHub release update center |
| CI / release | Android CI and tag-triggered signed release workflow |

The obsolete `com.meshlink` package and unused duplicate `mesh-protocol` Gradle module have been removed. The active app has one packet model and one repository/routing implementation.

## Implemented capabilities

### Networking

- Local LAN / same-WLAN private IPv4 discovery and TCP packet exchange.
- Bluetooth LE GATT.
- Bluetooth Classic RFCOMM with signed MeshLink identity HELLO and nonce challenge-response.
- Wi-Fi Direct and Wi-Fi Aware adapters.
- Optional Google Nearby Connections adapter; requires compatible Google Play services.
- Per-peer endpoint paths, persisted Automatic / Prefer / Only transport policy, disabled adapter filtering and sequential fallback.
- NFC NDEF and QR identity/bootstrap flows. These are not continuous packet transports.

All adapters remain unverified on physical multi-device setups. See [NETWORKING_MATRIX.md](NETWORKING_MATRIX.md) and [TRANSPORT_ACCEPTANCE_RESULTS.md](TRANSPORT_ACCEPTANCE_RESULTS.md).

### Messaging, sessions and routing

- Private text uses authenticated ephemeral P-256 ECDH sessions, transcript-bound HKDF-SHA-256 directional keys and AES-256-GCM `MLSE1` payloads.
- Verified peers establish sessions on discovery/reconnection; a maintenance loop begins rekey negotiation before the 15-minute session expiry.
- Simultaneous session offers use deterministic session-ID arbitration.
- Pending private text is retried after session negotiation.
- Group messages use a shared group AES-GCM key and are blocked while key/role updates remain unresolved.
- Store-and-forward, Epidemic, Spray-and-Wait and hop-limited relay code exists.
- Routing metadata is bounded to a 32-hop budget; duplicate visited IDs, visited relays and already-visited destinations are rejected.
- Packet replay cache and expiry checks are applied before normal delivery/forwarding.

### Groups

- Admin/member roles and admin-only role changes.
- Per-recipient durable group-key update records and acknowledgements.
- Per-recipient durable role-update outbox, recipient-bound ACK validation and reconnect retry.
- Idempotent duplicate role update handling for lost-ACK recovery.
- Stale updates are superseded when a newer role or key epoch takes effect.
- Legacy QR group invitations containing the raw shared group key remain disabled.

This is not yet a full all-member commit protocol. Offline members, membership removal and epoch convergence still require physical acceptance testing.

### File transfer

- Chunked offers and transfer with bounded sizes.
- Ephemeral session encryption for outbound file offers/chunks.
- Per-chunk and final SHA-256 verification.
- ACK correlation, pause/resume and process-death recovery.
- Verified inbound export through Android's Save As picker.
- Open/share actions and completion notifications.

Inbound legacy file decryption remains for compatibility; file ACKs still use legacy peer ECDH encryption.

### UI and operations

- Dark-first Compose interface with animations and polished shared components.
- Peer directory, local profile, private/group chats and message search.
- QR save/share/import and camera scanner.
- Networking and Android permission settings.
- File transfer dashboard, diagnostics and transport health.
- Built-in local bug reports and GitHub release update checker.
- Android permission declarations avoid broad storage/contact/SMS access.

## Database safety

- Room schema version: **9**.
- Initial checked-in database schema in repository history: version 3.
- Registered migration chain: 3→4→5→6→7→8→9.
- Destructive fallback migration has been removed. Unknown migration paths fail closed instead of erasing local data.
- Room schema export is enabled and KSP writes snapshots under `app/schemas`.
- Migration regression test covers the 3→9 SQL chain and checks that existing rows survive.
- Historical schemas 1 and 2 are unavailable; migrations are not guessed. An installation with one of those versions will preserve its database but may fail to open until a real compatible migration is supplied.

## Security limitations that remain open

1. **Mutable route metadata is not origin-signed.** The canonical signature excludes hop limit/count, visited path, spray budget, priority and source-cluster fields. Bounds and loop checks protect honest routing behavior, but a malicious relay can tamper with those fields. Authenticated per-hop route attestations are still required for stronger malicious-relay protection.
2. **Not all traffic is forward-secret.** Voice, direct location, group control, some emergency copies and file ACKs still use long-lived identity ECDH-derived encryption.
3. **Emergency broadcast payloads are plaintext.** Broadcast text and optional coordinates are visible to relays/receivers.
4. **Group epoch convergence is not a complete commit protocol.** Durable ACK/retry state exists, but disconnected member convergence and all-member commit semantics need more work.
5. **Session state is process-local.** Restart requires a fresh handshake. Proactive rekey and simultaneous-offer arbitration are implemented but need two-device loss/restart testing.
6. **Independent security review has not been performed.** Do not advertise MeshLink as audited or guaranteed emergency infrastructure.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for the detailed packet-encryption matrix.

## Build and release

- Application ID: `com.aistudio.meshlink.rx7kqm`
- Source version: `1.1`, versionCode `2`
- JDK: 21
- Gradle: official 9.3.1 wrapper committed (`gradlew`, `gradlew.bat`, wrapper JAR)
- Debug build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Android CI generates/verifies the wrapper, compiles debug APK and runs unit tests.
- A tag-triggered signed APK release workflow is configured. It requires protected GitHub Actions keystore/password secrets and has not yet been exercised with a production key/tag.
- Never commit signing material. Android upgrades require the same application ID and signing certificate.

See [ANDROID_RELEASE.md](ANDROID_RELEASE.md).

## Acceptance and release gates

| Gate | Current state |
|---|---|
| Kotlin/Room/unit test coverage | Automated tests present; latest CI result must be checked for the current head |
| Room migration 3→9 | Regression test added; real previous-APK upgrade still required |
| LAN / BLE / Classic / Wi-Fi Direct / Wi-Fi Aware / Nearby | Physical tests NOT RUN |
| NFC and QR bootstrap | Physical tests NOT RUN |
| Two-device session/rekey and group ACK recovery | Physical tests NOT RUN |
| Three-device A-B-C relay | Physical tests NOT RUN |
| File transfer process death / resume | Physical tests NOT RUN |
| Production signed APK and same-certificate upgrade | Not exercised |
| Independent security review | Not performed |

Do not mark the application production-ready or multi-device verified until the acceptance scenarios and evidence in [MULTI_DEVICE_ACCEPTANCE_TESTS.md](MULTI_DEVICE_ACCEPTANCE_TESTS.md) and [TRANSPORT_ACCEPTANCE_RESULTS.md](TRANSPORT_ACCEPTANCE_RESULTS.md) are completed.
