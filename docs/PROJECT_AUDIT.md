# MeshLink project audit

Audit date: 2026-11-17  
Repository reviewed: `abdulraheemnohri/MeshLink`  
Branch reviewed: `main`

## Executive summary

MeshLink is a substantial Android/Jetpack Compose offline-messaging codebase with a Room-backed data layer, identity and cryptography modules, transport adapters, routing, file transfer, voice/location features, diagnostics and settings. The source tree contains many working components, but source presence is not proof of reliable multi-device operation. The project must remain labelled **development / device-validation required** until CI and the physical-device acceptance matrix pass.

The Identity screen previously displayed QR codes and accepted pasted identity URIs, but did not open the camera to scan a QR. This audit adds an offline CameraX + ZXing scanner, permission-on-demand behavior, and QR identity import through the existing decoder.

## Screens and navigation found in source

- Home / dashboard
- Discovery
- Connections / trusted and blocked peers
- Network topology map
- Network bridge
- Chats hub, private chat and group chat
- File sharing and transfer queue
- Voice push-to-talk
- Location sharing
- Device identity and QR display/import
- Security and biometric lock
- Routing and store-and-forward queue controls
- Diagnostics and transport health
- Settings
- Emergency mode
- Lock screen and onboarding

## Existing source areas

- Android app: Kotlin, Jetpack Compose, Material 3 and Navigation Compose
- Persistence: Room database and DAOs
- Identity/security: ECDSA P-256 signing, ECDH P-256 key agreement, packet verification and peer identity flows
- Networking: Local LAN, BLE, Wi-Fi Direct, Wi-Fi Aware and Bluetooth Classic adapters are present in the app container
- Routing: packet expiry, duplicate suppression, queueing and multi-path endpoint selection
- File transfer: chunking, hashes, acknowledgements and resumable transfer code
- Background: foreground mesh relay service
- Device integrations: NFC intent bootstrap, QR identity exchange, microphone, location, biometrics and runtime permissions
- Build: Gradle version catalog and GitHub Actions Android CI

## People and trusted-peer directory

The Connections screen now presents a searchable peer directory:
- Search by peer alias, device ID or fingerprint.
- Filter all peers, online peers, QR-verified peers or blocked peers.
- Shows aggregate online, verified and total peer counts.
- Empty search/filter results have an explicit state.
- Existing verify, disconnect and block controls remain available.

Filtering is performed locally over the Room-backed peer list; no network request or remote account is required. A peer marked QR-verified is a local trust flag, not a guarantee that the peer is currently reachable.

## Local user profile

The app now has a local-only profile editor available from the Node navigation tabs.

- Display name (48-character limit), about text (160-character limit), status and avatar emoji.
- Local privacy preferences for display-name sharing, contact requests and online-status visibility.
- Profile values are stored in app-private SharedPreferences and survive app restarts.
- The profile is intentionally separate from the cryptographic device identity and does not alter public keys, peer IDs or existing trust records.
- The current profile is not automatically broadcast to peers. The online-status option is a saved preference only; presence broadcasting is not implemented.
- No cloud account, registration, remote sync or profile-photo upload is required.
- Robolectric tests cover persistence, trimming and field bounds.

Profile data is descriptive, not a secret store. Do not put passwords, recovery phrases or private keys in profile fields. A future peer-profile exchange must define consent, signature binding, versioning and anti-spoofing before publishing profile details.

## Android permission manager

- Settings provides per-feature runtime permission controls for camera, Bluetooth / Nearby Devices, Nearby Wi-Fi, location, microphone and notifications.
- Runtime permission requests are user initiated; camera, microphone and notification access are not required merely to start radio transports.
- File access uses the Storage Access Framework; broad storage/media permissions are intentionally omitted.
- System-managed permissions such as NFC, biometric, wake lock and foreground-service declarations are documented separately from runtime prompts.

## QR scanner changes in this audit

- Added CameraX camera2/core/lifecycle/view runtime dependencies.
- Added optional camera hardware declaration and runtime CAMERA permission.
- Added a QR-only ZXing analyzer using the Y plane from CameraX frames, including row/pixel-stride handling.
- Scanner is opened explicitly from Identity; permission is requested only then.
- Camera use case is bound while the scanner dialog is active and released when dismissed.
- Decoded text is sent to the existing MeshViewModel identity URI decoder. A QR scan is not itself trust approval; the existing cryptographic validation path decides whether the payload is accepted.
- No image or scan history is stored.

## Gaps and release gates

| Area | Current assessment | Required next action |
|---|---|---|
| Google Nearby Connections | Optional P2P_CLUSTER adapter with signed HELLO, nonce proof, and 1,000,000-byte application frame cap | Requires Play Services and current permissions; compile/test CI and two-device validation remain release gates |
| NFC | NDEF URI intent parsing/helper exists; end-to-end tag writer/reader handoff not verified | Validate actual tag read/write workflows on supported hardware; do not claim Android Beam |
| Bluetooth Classic | RFCOMM adapter and authenticated handshake code exist | Two-device pairing, reconnect, bidirectional delivery and process-restart tests |
| BLE / Wi-Fi Direct / Wi-Fi Aware / LAN | Source adapters exist | Two-device interoperability, large-frame, reconnect and screen-off tests per adapter |
| Multi-hop routing | Routing/relay code exists | Three-device A-B-C relay test, hop-limit, duplicate and expiry tests |
| File transfer | Chunking, ACK and resume code exists | Kill/restart tests, duplicate/reordered chunks, hash mismatch and two-device tests |
| Session security | Identity signatures and ECDH code exist; static ECDH is not forward secrecy | Independent protocol review and ephemeral authenticated session-key exchange before production claims |
| Background operation | Foreground relay service exists | Android 14–17 permission/notification, battery optimization and OEM process-kill tests |
| Release signing | CI debug build can use an owner-provided stable debug key; production release key is not configured by this workflow | Owner must configure and protect a production signing key; never commit it |
| Installation | Package ID must remain stable; debug certificate must match an existing installation | Use the exact same keystore to update; obtain ADB INSTALL_FAILED output for generic installer errors |

## Build identity and update safety

The current application ID remains `com.aistudio.meshlink.rx7kqm` to avoid silently changing the Android package identity. This audit increments the app to version `1.1`, versionCode `2`, so it can update a versionCode 1 installation **only if the signing certificate matches**. A version bump cannot solve a signing-key mismatch.

## Validation checklist

- [ ] GitHub Actions compiles the debug APK and runs all unit tests at the latest commit.
- [ ] Scanner permission grant, deny, re-request and dismissal are tested.
- [ ] QR scan imports a valid MeshLink identity and rejects malformed/unrelated QR content.
- [ ] Two physical Android phones exchange an identity and message over each supported direct transport.
- [ ] Three physical phones validate multi-hop and store-and-forward.
- [ ] File transfer resumes after app process termination and validates final SHA-256.
- [ ] Screen-off and battery behavior is recorded on supported Android versions.
- [ ] Production signing key, upgrade path and release artifact are validated.

## Scope note

This audit does not claim that every possible radio or network type is a native Android transport. Cellular/internet/satellite require explicit gateways; LoRa/Zigbee/Thread/HaLow require external radio hardware. QR and NFC are bootstrap channels, not mesh data links. Google Nearby Connections is integrated as an optional transport but still requires physical-device validation.


## QR scanner reliability

The offline QR scanner now reports camera provider/startup/binding failures in the dialog and offers a retry action. The retry path recreates its image-analysis executor rather than attempting to reuse a shut-down executor. This is source-level implementation; camera behavior must still be validated on physical devices.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for high-priority open protocol risks, including group invitation QR key exposure and the current long-lived ECDH key schedule. These are documented as release blockers, not presented as solved.


## Security hardening update — 2026-11-17

- Group QR export no longer embeds the group AES key; legacy group QR import is rejected and the group QR entry point is visibly disabled.
- QR identity imports now start untrusted. Existing peer identities are pinned: a changed signing key, agreement key, or fingerprint for an existing device ID is rejected and recorded as a critical security event.
- UI messaging now distinguishes local group key/member changes from actual remote key distribution or revocation.
- Regression tests cover key-free group QR output, rejection of legacy key-bearing QR payloads, untrusted initial imports, and rejection of peer key replacement.

Still open: the long-lived ECDH pairwise key schedule has not been replaced with an authenticated ephemeral session protocol. Group epoch delivery/acknowledgement is also not implemented. The above are mitigations, not a complete security protocol migration.


## Group key epoch delivery hardening — 2026-11-17

Group key rotation and member removal now create a new epoch in a Room transaction after checking that the local member is an admin. The routing engine sends signed, per-recipient encrypted `GROUP_KEY_UPDATE` packets to remaining trusted peers. Receivers require a trusted sender who is an admin in the local group, validate 256-bit key material, reject stale epochs, apply member revocation and send delivery acknowledgements. The UI reports the number queued or sent; acknowledgements remain asynchronous, so this is not an all-member commit guarantee.

Group invitations are accepted only from a trusted peer, must carry a valid group key, and must name the signed packet sender as creator. Invitations into an existing group require the sender to be an admin. Member records are added locally only when the invitation was sent or persisted to the store-and-forward queue. Group role changes remain disabled in the UI until they can be replicated and acknowledged safely.

Pairwise key wrapping still uses the legacy long-lived ECDH identity keys. Forward secrecy and a reliable all-member epoch acknowledgement/recovery protocol remain open security release gates.
