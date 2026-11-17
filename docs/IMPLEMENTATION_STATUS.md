# MeshLink implementation and release status

This document separates implemented source code from capabilities that have been verified on real Android devices. A class or UI screen is not evidence that a transport or mesh route works end to end.

## Group key update recovery

- Unresolved per-recipient group key updates are persisted in Room and can be retried from the group chat toolbar.
- Retry requires the local device to be a group admin and the intended peer to be QR-verified, unblocked, and have a usable agreement key.
- Reissued updates retain the original revoked-member identifier, so a retry after member removal does not silently lose revocation semantics.
- Older unresolved epochs are marked SUPERSEDED when a newer key epoch is distributed; only the current epoch's unresolved recipients block group messaging.
- Recipients that are missing, unverified, blocked, or not yet connected remain unresolved and keep group messaging gated until recovery succeeds.
- Database schema v8 adds the opt-in Nearby Connections setting; migration 7→8 is registered.

## Android permission coverage

- Settings now includes an Android Permission Manager with separate on-demand requests and live granted/missing counts for Camera, Bluetooth / Nearby Devices, Nearby Wi-Fi, Location, Microphone and Notifications.
- Radio startup permission lists are separate from optional feature permissions: relay startup does not require camera, microphone or notification permission.
- Manifest declarations cover Internet/network state, Wi-Fi controls, Bluetooth legacy and Android 12+ permissions, Nearby Wi-Fi, camera, location, microphone/audio routing, NFC, notifications, foreground services, wake lock and biometric use.
- NFC, biometric, network, wake-lock and foreground-service declarations are system-managed or normal permissions; they do not appear as runtime permission prompts.
- File access uses Android's Storage Access Framework document picker; broad storage/media permissions are intentionally not requested.
- Background location, contacts, SMS, call logs and broad storage access are intentionally absent because current MeshLink features do not justify them.
- Permission requests are optional and user initiated. Denied permissions leave their associated feature unavailable rather than silently granting access.

## Latest source-level implementation (not yet device-verified)

| Area | Current source state | Remaining verification / limitation |
|---|---|---|
| BLE | MTU-aware GATT writes, bounded application fragmentation/reassembly, per-transfer aggregate byte limits, bounded peer caches, sequential write callbacks and codec unit tests | Retry policy, peripheral/central role coverage, real-device throughput and long-transfer stability |
| Wi-Fi Aware | Bounded fragmentation/reassembly and per-frame framework send callbacks | Framework send success is not remote application delivery; test NAN availability, peer-handle lifecycle and packet-level ACK behavior on supported devices |
| Wi-Fi Direct | Group-owner/client role tracking; periodic best-effort client IP lookup from ARP; TCP acceptance response through the LAN listener | ARP access and DHCP mapping are OEM-dependent; prove both directions on physical phones |
| Local LAN / WLAN | Length-bounded TCP frames, UDP discovery, private-IPv4 filtering, bounded clients and per-peer rate limits; intended for same-network Wi-Fi and compatible hotspot subnets | Same-network discovery may be blocked by AP/client isolation; validate Wi-Fi, hotspot, USB tethering and Ethernet interfaces on real devices |
| NFC / QR | NFC URI intent import; QR identity display, manual URI import and offline CameraX + ZXing QR scanner | NFC tag write/read handoff still needs physical testing; QR is bootstrap only, not a continuous network transport |
| Bluetooth Classic | RFCOMM adapter is registered; explicit connect uses paired devices and authenticated identity handshake | Requires physical two-device acceptance testing |
| Google Nearby Connections | Optional P2P_CLUSTER adapter with signed identity HELLO, fresh nonce proof, byte packet framing, Settings toggle, Room v7→v8 migration, explicit relay lifecycle registration, and a 1,000,000-byte application frame guard | Requires Google Play services and nearby permissions; payload-transfer progress/failure correlation is not yet surfaced; no physical two-device test; unavailable on AOSP-only devices without Play services |
| File transfer | Chunk hashes, receiver chunk ACKs, resumable incomplete-chunk reads, final size/SHA-256 verification, bounded file size and sanitized names | Automated integration tests, restart tests, duplicate/reordered chunk tests and two-device validation |
| Peer identity | Signed first handshake, trust-on-first-use key consistency checks, unknown non-handshake packet rejection and bidirectional endpoint mapping | QR fingerprint verification remains necessary to authenticate identity out of band; static ECDH is not forward-secret |
| Routing | Bounded SharedPreferences-backed durable packet replay store, expiry pruning, persisted remaining Spray-and-Wait budget and route-aware queue flushing | Physical three-node relay tests, queue policy tests and storage-failure behavior remain required |

These entries describe source changes only. They do not mean the corresponding radio path has passed a physical-device acceptance test. Use docs/MULTI_DEVICE_ACCEPTANCE_TESTS.md to record the two-device and three-device acceptance runs.

## Diagnostics export and build regression coverage

- Diagnostics CSV serialization is isolated in a platform-independent helper and unit-tested for empty exports, quoting, commas, embedded quotes, multiline summaries and row ordering.
- The export action still serializes only the event records currently visible after filtering; it does not include chat bodies, transferred file contents, credentials or cryptographic keys.
- The identity QR screen owns its own remembered list scroll state; Settings owns a separate scroll state for section navigation.
- Settings explicitly opts into the experimental Compose FlowRow API used by its category chips.
- The source fixes above address the compile errors reported by the Android CI run for commit 1075f1f; the follow-up CI run is the release gate.

## Network selection status

The routing engine prefers the discovered endpoint transport and can attempt other registered transports as fallback. The persisted Automatic / Prefer / Only transport policy, per-transport enable/disable controls, per-peer endpoint paths and strict no-fallback behavior are implemented; see NETWORKING_MATRIX.md. See [NETWORKING_MATRIX.md](NETWORKING_MATRIX.md) for accurate support boundaries.

## Current engineering priorities

1. Reproducible Android builds and CI.
2. Correct transport addressing and framing.
3. Lifecycle-safe relay service and transport shutdown.
4. Authenticated peer identity and reviewed session-key establishment.
5. Multi-hop forwarding with duplicate suppression, expiry and hop limits.
6. Physical-device interoperability and battery testing.

## Transport acceptance criteria

### Bluetooth LE
- Discover peers without treating a BLE address as a cryptographic identity.
- Negotiate a usable ATT MTU, but do not assume the requested MTU was granted.
- Fragment application frames to the negotiated payload size.
- Include transfer ID, sequence number, total length and checksum.
- Reassemble with strict size limits and timeouts.
- Handle GATT write responses, disconnects and retries.
- Test both central/peripheral roles and simultaneous connections.

### Wi-Fi Direct
- Distinguish the group owner from group clients.
- Use the group-owner address for client-to-owner traffic only.
- Do not treat a discovered Wi-Fi P2P MAC address as an IP address.
- Define a listener lifecycle and a peer-to-peer handshake.
- Exchange authenticated device IDs after connecting.
- Test traffic in both directions for owner and client roles.

### Wi-Fi Aware
- Check API and hardware availability at runtime.
- Handle publish/subscribe session termination and peer handle expiry.
- Fragment payloads to the platform-supported message size.
- Add sequence numbers, bounded reassembly, acknowledgement and retry.
- Fall back cleanly when the device does not support NAN.

### Local LAN
- Treat discovery as untrusted input.
- Bind listeners only to intended local interfaces.
- Frame packets with a bounded length prefix.
- Apply socket timeouts and per-peer rate limits.
- Authenticate peers before accepting application messages.

## Security release gates

- Verify signatures against a previously trusted identity; never trust a public key merely because it arrived in the same unauthenticated packet.
- Bind sender, recipient, protocol version, packet type, expiry and routing metadata into authenticated data.
- Use a reviewed authenticated key-exchange/session protocol with forward secrecy for production E2EE.
- Do not claim forward secrecy from a static ECDH key pair.
- Keep group-key distribution authenticated and support membership revocation/key epochs.
- Enforce packet size limits, TTL, hop limits, replay protection and duplicate suppression.
- Do not log plaintext, private keys, session keys or location payloads.
- Provide a clear warning that emergency broadcasts are best-effort and are not guaranteed to reach anyone.

## Physical-device acceptance matrix

| Test | Minimum setup | Pass condition |
|---|---|---|
| Direct message | 2 Android phones | Authenticated encrypted message delivered |
| BLE framing | 2 Android phones | Payload larger than one GATT write is reassembled exactly |
| LAN framing | 2 phones on same isolated LAN | Multiple frames received without truncation |
| Wi-Fi Direct | 2 compatible phones | Owner/client traffic works in both directions |
| Multi-hop | 3 phones A-B-C | A reaches C only through B; B cannot read plaintext |
| Store-and-forward | 3 phones | B queues while C is absent and forwards after C returns |
| Duplicate handling | 3 phones | Repeated packet ID is delivered to the application once |
| Expiry | 2 or 3 phones | Expired packet is discarded and never relayed |
| Restart recovery | 2 phones | Persisted pending packets survive process restart |
| Malformed input | 2 phones / test harness | Oversized or invalid frames are rejected without crash |
| Battery/background | 2 physical phones | Behavior and OS restrictions are documented under screen-off conditions |

## Release status

No release should be described as production-ready until the build, unit tests, physical-device transport matrix and independent security review have passed. CI can verify compilation and automated tests; it cannot certify radio interoperability or cryptographic design.


## Universal transport policy integration

The persisted Automatic / Prefer / Only transport policy is now exposed in Settings and ViewModel. The packet dispatcher enforces disabled transport preferences and strict Only mode. It avoids cross-adapter fallback using an incompatible endpoint locator. Same-device destinations are handled as local loopback delivery without a radio. Unit tests cover persistence, strict mode, preference ranking, disabled transports, payload limits and fallback control.

NFC identity URI import is wired through the existing NDEF_DISCOVERED intent handler. This is bootstrap only; Android Beam is deprecated/removed on modern Android and no arbitrary NFC phone-to-phone data transport is claimed. Bluetooth Classic RFCOMM is registered and has a source-level authenticated connect path, but remains unverified on physical devices. Google Nearby Connections remains unimplemented.


## Multi-path transport failover update

- The routing engine stores one endpoint per peer per transport, preserving LAN, Wi-Fi Direct, Wi-Fi Aware and BLE locators independently.
- Direct packet sends rank the peer's live endpoints using the saved transport preference, signal strength and adapter availability.
- Failed sends can retry a different discovered endpoint for the same peer when fallback is enabled and packet-size limits permit it.
- A disconnect removes only the endpoint for that transport; other paths to the peer remain available.
- Strict Only mode never sends through a transport outside the configured preference set.
- This implements sequential failover, not simultaneous link aggregation. It does not imply Bluetooth Classic, Nearby Connections, NFC data transport, cellular or satellite adapters are implemented.


## Transport policy correctness hardening

The routing dispatcher now treats `ONLY` as a strict single-path mode: even when the persisted fallback toggle is true, it attempts only the highest-ranked selected transport. `PREFER` and `AUTOMATIC` continue to honor the fallback toggle. The policy rule is centralized in `TransportPolicy.permitsFallback()` and covered by unit tests. This is a source-level fix; CI and physical-device delivery still need to pass before claiming verified behavior.


## Universal discovery coordinator — relay lifecycle integration

`UniversalDiscoveryCoordinator` coordinates start, scan and stop operations across registered `MeshTransport` adapters. It deduplicates peers by MeshLink device ID while retaining independent transport-specific endpoints, exposes a live peer snapshot and event stream, and prunes stale endpoint records after a configurable timeout. Adapter failures are isolated per transport.

The app-scoped coordinator is now connected to `MeshRelayService`: relay startup starts only the LAN, BLE, Wi-Fi Direct and Wi-Fi Aware adapters enabled in the user's settings, performs an initial discovery pass, and relay shutdown stops registered adapters through the coordinator. The ViewModel exposes the coordinator's live endpoint snapshot to the UI layer. Explicit start/stop selection is covered by unit tests.

Bluetooth Classic is registered as an adapter but is not automatically started by the relay because there is no user-facing enable setting in the current settings model. Google Nearby remains unimplemented and requires its supported Play services dependency and runtime availability; NFC remains identity/bootstrap only. The existing Wi-Fi Direct, Wi-Fi Aware, BLE and LAN adapters remain subject to their documented hardware and physical-device acceptance tests.

## Capability boundary reminder

NFC is for short-range identity/bootstrap handoff, not continuous mesh data transport. LoRa and satellite require compatible external hardware/gateways. Android background radio behavior is OEM- and permission-dependent. Release signing requires the owner's private keystore and must never be committed to Git.


## Live discovery dashboard integration (2026-11-17)

- The app-scoped universal discovery coordinator now publishes transport-specific endpoint records to the discovery UI through `MeshViewModel.discoveredEndpoints`.
- The Nearby Mesh Nodes screen now renders a Live Transport Paths panel grouped by peer identity, showing each observed transport, endpoint address/port when available, and age of the last observation.
- This panel reports discovery observations only; it does not imply that a peer is authenticated, connected, or currently reachable. Existing peer connection and trust state remain separate.
- CI previously compiled the debug APK but rejected `UniversalDiscoveryCoordinatorTest` as an invalid JUnit test class. The test coroutine result type was made explicit with `runBlocking<Unit>`; the resulting workflow is being re-checked.
- Device-level validation is still required for LAN, BLE, Wi-Fi Direct, and Wi-Fi Aware radios, especially Android permission and background restrictions.


## Bluetooth Classic transport controls (2026-11-17)

- Added a persisted `bluetoothClassicEnabled` setting, defaulting to off so RFCOMM does not start without an explicit user choice.
- Added a Room 3→4 migration that adds the setting with a false default, preserving existing local settings and data.
- Added a Bluetooth Classic RFCOMM toggle under Active Transports & Relay Service. The description clarifies that this implementation targets already-bonded devices and requires Android Nearby Devices permission; it does not silently pair or make the phone discoverable.
- Relay startup now includes Bluetooth Classic only when enabled. When radio settings change while relay is running, the ViewModel stops disabled adapters, starts enabled adapters, and triggers discovery without restarting the foreground service.
- The Universal Network Manager now describes Bluetooth Classic as a registered adapter; NFC and QR remain identity/bootstrap mechanisms, not packet transports.
- Device validation remains necessary: confirm Bluetooth permission grants, bonded-device discovery, RFCOMM connection, mutual identity proof, and radio shutdown on supported Android versions.


## Authenticated Bluetooth Classic connection actions (2026-11-17)

- Added a transport-level `connect(endpoint)` contract and coordinator routing so the UI can establish a session without sending a dummy application packet.
- Bluetooth Classic now connects to the selected bonded device's RFCOMM service, exchanges the signed MeshLink identity hello, completes the fresh-nonce challenge/response, and returns success only after the authentication deferred completes.
- Connection-up events are emitted only after the nonce proof succeeds; the earlier pre-authentication connection-up event was removed. Failed/expired authentication closes the socket and emits diagnostic events.
- The Nearby Mesh Nodes transport-path panel now exposes a Connect action for Bluetooth Classic endpoints. The ViewModel reports the authenticated result and records a network event on success.
- Added a coordinator unit test proving endpoint connection is dispatched only to the adapter matching the endpoint transport.
- Discovery scans now call `UniversalDiscoveryCoordinator.discoverAll()`, which only scans running adapters; the screen no longer invokes the routing engine's unconditional all-adapter scan.
- Diagnostics now distinguish Bluetooth hardware support, radio enabled state, and Nearby Devices permission; Wi-Fi Direct/Aware readiness also accounts for Nearby Wi-Fi permission.
- Physical validation remains required on two Android devices with Bluetooth enabled, MeshLink installed, bonded pairing completed in Android settings, and Nearby Devices permission granted.


## Discovery identity normalization and final connection safeguards (2026-11-17)

- When a transport first reports a hardware address and later reports the authenticated MeshLink device ID for the same transport/address, the coordinator replaces the provisional entry rather than displaying duplicate peers.
- Bluetooth Classic now emits its connected event only after the signed fresh-nonce response has been verified; the earlier pre-authentication connected event is not emitted.
- Added coordinator tests for transport-specific connect dispatch and provisional-to-authenticated identity replacement.
- Scan Now uses the coordinator's running-adapter set, preventing disabled transports from receiving discovery triggers.
- CI for this final batch must be checked against the latest main commit before release. No physical-device acceptance run has been performed from this environment.


## Transport runtime health (2026-11-17)

- The discovery coordinator now publishes a `TransportHealthSnapshot` for each registered adapter.
- Snapshots expose current running state, last successful start time, last successful discovery time, and the latest start/discovery/stop error.
- The ViewModel exposes the health stream and the Diagnostics screen displays each adapter's runtime state and last scan time.
- Unit coverage verifies successful startup and scan timestamps, failed adapter startup reporting, and stopped-state updates.
- This is runtime lifecycle telemetry, not proof of peer reachability or successful end-to-end packet delivery. Physical-device acceptance remains required.


## Durable packet replay protection (2026-11-17)

- Added `PacketReplayStore`, backed by app-private SharedPreferences, to retain accepted packet IDs across process restarts.
- Packet IDs are checked before expensive processing and atomically recorded after the sender identity/signature checks, closing the concurrent duplicate-processing window.
- Expired entries are pruned and the store enforces a configurable bounded capacity (10,000 entries by default).
- The store fails closed when persistence cannot be committed; invalid, blank, overlong and expired IDs are rejected.
- Robolectric tests cover persistence across store instances, expiry, invalid IDs and capacity eviction.
- This is bounded local duplicate suppression, not a replacement for end-to-end delivery acknowledgements or cryptographic replay-resistant session protocols.


## Camera QR scanner (November 2026)

- Identity screen now provides an offline camera-based QR scanner using CameraX and the bundled ZXing core decoder.
- Scanner accepts QR codes only, returns decoded text to the existing identity URI decoder, and does not automatically trust arbitrary scanned content.
- Camera permission is requested only when the user opens the scanner. The camera use-case is bound while the scanner dialog is active and released on dismissal.
- Camera hardware is declared optional so devices without a camera can still install and use MeshLink's manual URI import and QR display.
- No captured image or scan history is persisted.
- Validation still required: compile/unit CI, permission denial/retry, low-light focus, rotation, and two-device QR identity import on physical Android devices.


## Final integration checkpoint — 2026-11-17

The following source-level integration is present on `main`:

- Android Permission Manager groups optional runtime permissions by feature and requests only the selected group's missing permissions. The manager also links to Android App Settings for permissions that have been permanently denied or need manual adjustment.
- Camera permission remains on-demand for QR scanning; microphone permission remains optional for voice features; notifications are requested only on Android 13 and newer.
- Bluetooth runtime permissions are version-aware (Android 12+ Nearby Devices permissions, legacy location permission for older BLE discovery). Nearby Wi-Fi permissions are version-aware (Android 13+ `NEARBY_WIFI_DEVICES`, legacy location permission on older versions).
- File access uses the system document picker. MeshLink does not request broad external-storage access, contacts, SMS, call logs or background location.
- Google Nearby Connections is an optional P2P_CLUSTER adapter and requires compatible Google Play services. It is not a universal transport and must gracefully report unavailable services.
- CI run 44011289600 for commit 8efeb51723485ec245c0a2cdd4da3d4f5ae48858 completed successfully, including the repository's Android verification job.

### Release gate — do not mark as production-ready yet

CI success proves the checked-in project builds and its automated checks pass in GitHub's environment. It does not prove end-to-end radio interoperability, background reliability, payload transfer completion, or security under hostile network conditions. Before a public production release, complete the two-or-more-device acceptance plan on supported Android versions, including permission denial/recovery, BLE, bonded Bluetooth Classic, Wi-Fi Direct, Wi-Fi Aware, LAN, Nearby Connections, encrypted message delivery, file transfer interruption/resume, group-key rotation, and relay restart/recovery.

The app's release keystore must remain private and outside Git. A signed release APK and physical-device test evidence are not produced by this documentation checkpoint.


## Verified file-transfer replay recovery (2026-11-17)

- A receiver that has already assembled and SHA-256-verified a file now handles a retransmitted chunk idempotently.
- The receiver validates the retransmitted chunk's encoded length and SHA-256, then compares its bytes against the corresponding range in the verified destination file before re-sending the chunk ACK and final verification ACK.
- A mismatching replay is rejected and recorded as a security event; verified destination content is never overwritten by a late duplicate chunk.
- Added JVM unit tests for exact chunk matching, wrong bytes, out-of-range reads, missing files and negative offsets.
- This closes the lost-ACK retry case at the receiver state machine. End-to-end retry behavior, process-restart recovery and multi-device behavior still require Android CI and physical-device tests.

## Verified received-file export (2026-11-17)

- Added a transfer-manager export operation for inbound transfers in VERIFIED state only.
- Before copying, the operation rechecks the source file size and SHA-256 digest; a mismatch is blocked and recorded as a security event.
- Exports use Android MediaStore Downloads under `Downloads/MeshLink`, with `IS_PENDING` publication so partially copied files are not exposed.
- The transfer dashboard exposes the save action only for verified incoming files. No broad storage permission is requested.
- This implementation currently requires Android 10 (API 29) or newer. Older Android versions return an unsupported result; a Storage Access Framework fallback remains future work.
- The export action is not a file opener or share action; those remain separate follow-up work.

### Verified download export hardening

- The Android CI compile failure caused by the file-sharing screen's missing `onExport` callback is fixed.
- Export integrity validation is extracted into `VerifiedFileIntegrity` and covered by JVM tests for exact hash/length, tampered content, length mismatch, missing files, and malformed hashes.
- Export remains restricted to inbound `VERIFIED` transfers and Android 10+ MediaStore Downloads. Older Android versions still need a Storage Access Framework document-picker flow; do not treat this as completed until that UI path is implemented and tested.
- The latest Android CI run is pending; compilation and unit-test success must be confirmed from GitHub Actions before calling this milestone green.

### Transfer recovery, cross-version export, and completion UX

- Added a Storage Access Framework `CreateDocument` export path for verified inbound files. This works on Android versions below 10 and lets the user choose the destination; it rechecks file size and SHA-256 before copying.
- Added post-export Open and Share actions using the selected document URI with read-grant flags.
- Added startup reconciliation for transfers left in `TRANSFERRING` after process death. Outgoing transfers are paused with acknowledged-chunk progress recalculated; incoming transfers are paused with partial chunk count reconciled so the sender can resume after reconnect.
- Added best-effort completion notifications for both sender and receiver after final SHA-256 verification. Notifications are optional and a denied notification permission does not stop file delivery.
- Remaining verification: run Android CI, test SAF providers and notification permission behavior on Android 10/13/17, and perform a two-device kill/relaunch/resume acceptance test. The in-memory pending-ACK waiters are intentionally recreated on reconnect; they are not persisted across process death.


## Built-in support center and release updates (2026-11-17)

- Settings now includes a built-in bug report form with issue category, severity, bounded title/description fields, and an optional basic device/app environment block.
- Reports are first saved under the app-private `filesDir/bug-reports` directory. MeshLink does not silently upload reports, attach private chat/file contents, or include cryptographic secrets. The user is sent to a prefilled GitHub issue form and must review, sign in and submit manually. Local reports remain on-device.
- The update center queries the official GitHub `releases/latest` endpoint over HTTPS, displays release notes and compares the latest stable tag with the installed version.
- If a release includes an APK asset, the user can download it with Android DownloadManager and explicitly open the package installer. Android's unknown-app install permission is requested only when needed and only after the user chooses installation.
- APK installation is subject to Android package signature checks. An update must use the same application ID and signing key as the installed build; this feature cannot make a differently signed APK install over an existing app.
- The update checker requires internet access. MeshLink's messaging and local mesh features remain independent of the release-check service.
- Remaining release validation: run Android CI; verify a real tagged GitHub release with APK asset, no-APK release, offline/timeout/error paths, download completion/failure, Android 8+ install permission flow, same-signature upgrade and signature mismatch rejection. No APK has been installed or verified from this source change.


## Identity QR export/import and group-management UX (2026-11-17)

- Identity screen can render a high-resolution QR PNG, save it to the public Pictures/MeshLink collection through MediaStore, and share the PNG through Android's chooser with URI read permission.
- Identity screen can open the Android document picker, decode a QR from an image, and pass only a `meshlink://peer?` identity payload into the existing peer identity verification/import flow. Non-MeshLink QR codes are rejected at the UI boundary; the existing cryptographic signature/key validation remains authoritative.
- No broad storage permission is requested; image import uses the Storage Access Framework and image export uses MediaStore.
- Group list now supports name/description/invite-code search and category filters (Community, Event, Neighborhood, Rescue), with a no-results state.
- Group creation only offers QR-verified, unblocked peers as invitation targets.
- Group member controls now reflect the local member's ADMIN role. Admins can distribute role changes, invite trusted peers over authenticated peer connections, rotate keys, retry pending key updates, and remove members with a confirmation that explains key rotation. Non-admin controls are disabled in the UI; routing/repository authorization remains the enforcement boundary.
- Legacy group QR invitations and pasted legacy group invite URIs remain disabled because they embed the shared group encryption key. Do not re-enable them. A future QR invitation must be recipient-specific and encrypted to that recipient's verified public key, with replay protection and expiry.
- Remaining validation: Android CI; save/share PNG on Android 10+; import QR from Gallery/Files and malformed/non-MeshLink image tests; test a high-resolution photo; two-device group role change, member removal, key rotation, ACK/retry, and offline member recovery. Physical device validation has not been performed by this code change.


## Database migration safety (2026-11-17)

- Room schema version is now 9. The repository history confirms the initial checked-in database schema was version 3; migrations 3→4→5→6→7→8→9 are registered in order.
- Removed `fallbackToDestructiveMigration(dropAllTables = true)`. An unknown or unsupported upgrade path now fails closed rather than deleting the user's local identity, peers, conversations, groups, pending packets, event history or transfer metadata.
- Enabled Room schema export and configured KSP to write schema snapshots under `app/schemas`.
- Exposed migration objects internally and added a Robolectric regression test that applies every migration from version 3 through version 9, checks new columns/indexes, and verifies pre-existing settings and group-member rows survive. Added a Room DAO test for durable per-recipient role-update status and ACK timestamps.
- Version 1 and 2 database schemas are not present in repository history or schema snapshots. They are deliberately not guessed. If an installation with either version exists, the app will now preserve its database and report a migration-path error instead of silently wiping it. A compatible migration requires the actual historical schema.
- Remaining validation: run `testDebugUnitTest` and `assembleDebug` on CI; capture and commit the generated Room schema JSON for version 9; verify an upgrade using a real version-3 database fixture and an upgrade from the previous published APK. Do not call migration safety fully release-verified until those checks pass.


## Durable group role synchronization (2026-11-17)

- Added Room schema version 9 and migration 8→9 for `group_role_updates`, with indexes for group and recipient lookups.
- Every role change now atomically updates the local member role and batch-persists a durable per-recipient outbox record containing packet ID, group/epoch, recipient, target member, role, issue timestamp, delivery status and acknowledgement timestamp before any network send.
- Delivery ACKs are correlated to the recorded intended recipient. A mismatched peer cannot mark another recipient's role update acknowledged.
- On peer discovery/reconnection, unresolved role updates are retried. Expired queued packets can be replaced with a new packet ID while preserving the original role issue timestamp.
- Receiver application is idempotent for an exact duplicate role update, allowing a retry to be acknowledged when the original ACK was lost. Older updates are rejected; newer role decisions and key epochs supersede stale outbox records.
- Group messages remain blocked while either key-epoch updates or role-update records are unresolved.
- Remaining validation: CI migration/DAO tests; two-device role promotion/demotion with lost ACK; offline member reconnect; duplicate role packet replay; epoch change during pending role delivery; and verification that stale role outbox entries are superseded without blocking group traffic indefinitely.


## Session reliability and rekey (2026-11-17)

- Verified peers now negotiate an ephemeral session automatically when a direct endpoint is discovered or reconnects, rather than waiting for the first user message.
- Added a lightweight 30-second session maintenance loop. Established sessions begin rekey negotiation 60 seconds before the 15-minute key lifetime; expired session key byte arrays are cleared when detected.
- Added deterministic arbitration for simultaneous offers: both peers choose the lexicographically smaller session ID. This avoids crossed concurrent handshakes installing different traffic-key pairs.
- Added unit tests for the arbitration rule in both peer perspectives.
- Existing 90-second handshake expiry and automatic private-text retry remain in place. Paused outgoing file transfers resume when the session-established event is emitted.
- Remaining validation: two-device simultaneous reconnect/rekey, packet loss during rekey, session expiry while a file transfer is paused, process death during an offer, and old/new app version interoperability. The maintenance loop and radio behavior still require physical-device testing.


## Transport and multi-hop acceptance preparation (2026-11-17)

- Rewrote the networking matrix to match the current source adapters and clearly separate implemented source, Android platform requirements and physical acceptance status.
- Added `docs/TRANSPORT_ACCEPTANCE_RESULTS.md` as a per-transport evidence tracker. LAN, hotspot, BLE, Bluetooth Classic, Wi-Fi Direct, Wi-Fi Aware, Nearby, NFC and QR physical results are explicitly NOT RUN; no device results have been fabricated.
- Corrected protocol documentation that previously claimed Bluetooth Classic and Nearby were not registered transports.
- Extracted multi-hop TTL and visited-path decisions into `MeshForwardingPolicy`; relay forwarding now rejects a node already in the visited path, consumes exactly one hop and avoids sending to the previous sender or a previously visited next hop.
- Routing now rejects a destination already present in the packet's visited path and records loop-drop diagnostics.
- Added JVM regression tests for hop decrement, visited-path loop rejection, destination stop and next-hop filtering.
- Expanded the multi-device acceptance plan with simultaneous session negotiation, durable role ACK recovery, session process-death recovery and TTL/loop abuse scenarios.
- Remaining validation: run Android CI and then execute the physical transport and A-B-C relay tests. The code-level policy tests do not prove RF interoperability or end-to-end relay behavior.


## Release engineering preparation (2026-11-17)

- Added a tag-triggered signed APK release workflow. It restores a release keystore from GitHub Actions secrets outside the repository, builds `assembleRelease`, verifies the APK with `apksigner`, extracts package/version metadata from the APK, records SHA-256 and publishes the APK plus verification evidence.
- Release signing key alias is configurable through `KEY_ALIAS`; the application ID remains fixed for upgrade compatibility.
- Added ignore rules for JKS, keystore, PKCS#12 and PFX signing material.
- Added `docs/ANDROID_RELEASE.md` with required secrets, tag/version policy, signature verification and upgrade warnings.
- The release workflow has not been exercised with production signing secrets or a real tag. No signed production APK is claimed.
- Generated and committed the official Gradle 9.3.1 wrapper JAR, `gradlew` and `gradlew.bat` from the CI runner. Android CI now verifies the wrapper and uses `./gradlew` for debug compilation and unit tests. A Windows local build can use `gradlew.bat`.


## Legacy source cleanup (2026-11-17)

- Removed the unreferenced `com.meshlink` `PeerRepository` and legacy `MeshConnectionService`. Repository code search found no consumers; the old service was not declared in the Android manifest. Active peer persistence remains in `com.example.data.repository.MeshRepository`, and active Wi-Fi Direct transport remains in `com.example.transport.WifiDirectTransport`.
- Removed the unused `mesh-protocol` Gradle module after confirming the app did not depend on it. Its duplicate `MeshPacketModel` and proto schema were not generated, referenced or integrated with the active `com.example.core.protocol.MeshProtocol` implementation. Keeping two independent packet models risked protocol drift.
- The active app now has one packet model and one active repository/routing implementation.
- Remaining validation: Android CI after module removal; repository-wide reference scan and APK build.


### Route metadata hardening and open relay-integrity limitation

- Route metadata now enforces a 32-hop total budget, bounded hop counters, a visited-path limit of 32 and unique visited IDs before dispatch/receive.
- A relay still decrements the hop budget exactly once and cannot forward to a visited peer or a packet destination already in the path.
- Source audit confirmed that the origin signature intentionally excludes mutable hop/path fields. A malicious relay can therefore tamper with these routing hints without invalidating the origin signature. The path is not authenticated provenance; a versioned per-hop attestation design remains an explicit security gate.


## Chat connection modes (2026-11-17)

- Exposed all five routing modes in both private and group chat: Autonomous, Direct, Store & Forward, Epidemic and Spray & Wait.
- Chat selectors display the selected mode's routing summary and have stable UI test tags.
- The selected mode is initialized from and persisted to `SettingsEntity.defaultRoutingStrategy`, so the choice is retained across chats and app restarts.
- Private messages already pass the selected mode to the routing engine; group messages now also pass the selected mode through the ViewModel to `MeshRoutingEngine.sendGroupMessage`.
- Added protocol round-trip coverage for every routing enum value and display label.
- Transport adapter selection remains independent from routing strategy selection; selecting Nearby Connections or LAN does not silently change the DTN forwarding strategy.
- Physical acceptance remains required to verify Direct delivery, delayed Store & Forward recovery, Epidemic duplicate suppression, Spray & Wait copy-budget behavior and Autonomous decisions across multiple phones.


## Connection / routing mode enforcement (2026-11-17)

The five message routing modes are available in private and group chat controls and persist as the default setting for new messages. This update makes their delivery semantics explicit in the routing engine:

- **Direct:** one-hop only. Private messages do not fall back to known multi-hop routes, relays or offline queueing. Group messages are sent only to currently reachable verified group members; if none can be reached, the message is marked failed and the UI reports why.
- **Store & Forward:** attempts a direct path first. Group messages are sent to one reachable verified member for subsequent relay; when no group member is reachable, the encrypted packet is persisted in the DTN queue.
- **Epidemic:** fans out to eligible unvisited peers; group packets are restricted to verified, unblocked members of that group. Duplicate suppression and route metadata remain in force.
- **Spray & Wait:** uses the packet's bounded remaining copy budget; group fan-out is restricted to verified, unblocked group members.
- **Autonomous:** resolves at send time using battery level, direct reachability and available peer count. Low battery (20% or below) selects Store & Forward; otherwise direct reachability selects Direct, three or more available peers selects Spray & Wait, one or two selects Epidemic, and no peers selects Store & Forward.

The pure RoutingStrategyPolicy has JVM tests for the five modes and autonomous decision boundaries. Transport selection (Automatic / Prefer / Only and per-radio enablement) remains a separate policy and is not changed by selecting a routing mode.

CI and physical acceptance for these latest changes are pending. Real-device verification of group relay behavior and all five modes is still required.


## Interactive topology and diagnostics pass (2026-11-17)

- Topology node selection is now available through both canvas tap targets and a keyboard/screen-reader friendly known-node button list.
- Selected-node inspection shows trust/block/connection state, active transport, identity fingerprint, relay counters, and persisted routes that reference the selected peer. It explicitly reports when no route record exists; it does not infer a path from the drawing.
- Diagnostics event history now supports free-text search across event summary, transport, type and peer ID, plus event-type filters and an explicit empty state.
- Event timestamps include the date, and peer IDs are shown when present. Search and filter controls expose stable test tags.
- These are UI/source improvements. Android accessibility services, screen sizes and radio-driven topology updates still require device acceptance testing.


## Peer action safety and diagnostics export (2026-11-17)

- Peer trust-marker changes, block/unblock and disconnect now require an explicit confirmation dialog. Dialog copy explains the scope and retained local records; verification copy reminds users to compare fingerprints through a trusted channel.
- Diagnostics supports exporting the currently filtered event records to CSV through Android's system document picker. The export contains timestamp, transport, event type, peer ID and summary only; it does not export chat contents, files, keys or credentials.
- The user chooses the destination. The UI reminds users to review exported diagnostic data before sharing it. Export failures are surfaced in the screen.
- The onboarding screen already permits continuing without granting every optional capability; users can grant permissions later from Diagnostics/Settings. Validate permission denial, picker cancellation and storage-provider behavior on physical Android versions.
- Android CI for this source revision is pending; do not treat these source changes as device-validated.
\n\n### Diagnostics CSV spreadsheet safety (2026-11-17)\n\n- CSV records now use CRLF separators and retain correct quoting for commas, embedded quotes and multiline summaries.\n- Every cell is checked for spreadsheet formula prefixes after leading whitespace/BOM characters. Values beginning with `=`, `+`, `-` or `@` are prefixed with an apostrophe before CSV quoting to prevent untrusted peer-controlled event text from being evaluated as a spreadsheet formula.\n- JVM tests cover each formula prefix, leading whitespace/BOM, empty output, row order, quoting and multiline values. This is export hardening, not a guarantee that every spreadsheet application handles CSV identically; review files before sharing.\n\n\n## Transport coordinator cancellation safety (2026-11-17)\n\n- Adapter start, stop, connect and discovery operations now preserve coroutine cancellation instead of converting it into an ordinary per-radio failure result.\n- Ordinary adapter exceptions remain isolated, so one broken radio does not prevent other selected adapters from starting or stopping.\n- Added a regression test proving cancellation during startup propagates and later adapters are not started.\n- This prevents cancelled relay lifecycle work from continuing through the coordinator; physical radio shutdown behavior still needs device validation.\n

## Durable relay retry and lifecycle recovery (2026-11-17)

- Pending DTN packets now use persisted attempt count and last-attempt timestamp to apply bounded exponential retry backoff. First delivery attempt remains immediate; subsequent attempts grow from 15 seconds to a maximum 15 minutes.
- The attempt timestamp is written to Room before network dispatch, so a process death during a send does not reset the retry schedule.
- Relay startup now preserves coroutine cancellation, starts only configured adapters, and marks the relay active only if at least one adapter reports running. Partial radio failures remain isolated; total startup failure is logged and radios are stopped.
- Maintenance operations preserve cancellation and report ordinary flush/cache-purge failures to the network event log instead of silently swallowing them.
- Explicit stop now waits for startup and maintenance jobs to cancel before shutting down radios and marking connections disconnected.
- Existing pending packet schema already contained attemptCount and lastAttemptAt, so this change requires no Room schema migration.
- Remaining verification: CI unit tests/build, process-kill/relaunch test with a pending packet, and physical multi-hop A→B→C recovery. Backoff controls retry pressure; it does not prove delivery or guarantee Android will restart a killed service.


## Relay process-death recovery (2026-11-17)

- Relay startup now reconciles persisted peer and connection rows before enabling radios. Peers are marked disconnected and active CONNECTED / HANDSHAKING connection-history rows are closed with a recovery timestamp; stale live-state is never presented as an active connection after process death.
- The Room transaction updates peer and connection state together. Durable pending DTN packets are retained, while expired packets/messages and stale routes are purged through the existing expiry policy.
- Startup records a PROCESS_RECOVERY network event with the retained unexpired queue count and purge count, then starts the enabled transports. Reconnection events and the existing maintenance loop resume queue delivery.
- Added a Room/Robolectric regression test for stale connected/handshaking rows while preserving already-closed history.
- Existing PendingPacketRetryPolicy and DAO tests already cover persisted attempt counters, timestamps, exponential backoff, clock rollback, and retry boundaries; no duplicate retry policy was introduced.
- Delivery remains at-least-once across a crash between radio send and local ACK persistence. Receivers must retain duplicate suppression/idempotent handling; this is not a claim of exactly-once delivery.
- Remaining validation: Android CI; kill the app process with queued packets, relaunch and confirm the outbox survives; reconnect two physical devices and confirm eventual ACK/removal; verify no stale connected UI state before rediscovery.


## DTN retry concurrency hardening (2026-11-17)

- Queue flushing is serialized within the routing engine with a coroutine mutex. The lock is always released in a finally block, including cancellation and early returns.
- Before sending, each retry atomically claims the exact persisted outbox revision using message ID, expected attempt count, and expected previous-attempt timestamp. A second worker with a stale snapshot cannot claim or transmit the same queued row concurrently.
- Attempt metadata is committed before radio I/O. A process death after the claim therefore retains the backoff window instead of causing an immediate retry storm.
- Added a Room regression assertion proving the first worker claim succeeds, a stale competing claim is rejected, and a fresh revision can be claimed afterward.
- This reduces concurrent duplicate sends; it does not make radio delivery exactly-once. A crash after a receiver accepts a packet but before the sender records completion can still cause a later retry, so packet-ID replay suppression and idempotent receive handling remain required.


## DTN queue fault isolation (2026-11-17)

- Store-and-forward flush now isolates ordinary per-packet failures so one failed row or transport dispatch does not abort attempts for later queued messages.
- Coroutine cancellation is rethrown immediately; it is never converted into a packet failure.
- A claimed attempt remains persisted after a failure, preserving the existing retry backoff and at-least-once delivery semantics.
- Failure diagnostics are best-effort and do not block the rest of the queue if the diagnostics store is unavailable.
- CI action references are aligned across both jobs: checkout v6, artifact upload/download v5, and Gradle wrapper validation v6. The Gradle setup action also performs wrapper validation.
- Android CI success validates compilation and JVM tests only; radio and multi-device acceptance still require physical devices.


## BLE callback permission-race hardening (2026-11-17)

- BLE scan callbacks now resolve the remote Bluetooth address once inside a `SecurityException` guard. If Bluetooth Connect permission is revoked between scan delivery and address access, the callback safely ignores that result instead of crashing the callback thread.
- The resolved address is reused for both the discovered-device index and endpoint, avoiding a second permission-sensitive framework access.
- Empty advertised service identifiers are rejected as identity hints and fall back to the normalized Bluetooth address.
- The Android CI run that exposed the missing `ContextCompat` import is superseded by the import fix; latest compilation and tests remain pending confirmation.


## BLE GATT receive-memory bounds (2026-11-17)

- BLE inbound peer tracking is capped at 16 peers, and each peer's frame reassembler accepts at most two incomplete transfers at a time. With the 256 KiB packet limit, the configured worst-case incomplete payload budget is approximately 8 MiB, before object/array overhead.
- The GATT callback now resolves the remote address under a permission-revocation guard and reuses that value for cleanup, reassembly and packet events. A revoked Bluetooth Connect permission causes the callback to drop the event safely.
- Added a codec regression test proving the configured in-flight transfer limit evicts an older incomplete transfer while allowing the newer transfer to complete.
- This bounds application-level reassembly memory; it does not authenticate the BLE link by itself. MeshLink's packet/session verification remains responsible for peer authenticity.


## Multi-transport lifecycle and cancellation hardening (2026-11-17)

- BLE stop now attempts scanner, advertiser and GATT-server cleanup independently even when Nearby Devices permission has been revoked. A failed cleanup call no longer skips later resource releases.
- BLE GATT packet sends now disconnect and close their client GATT in a finally block on success, timeout, failure or coroutine cancellation. Pending callbacks are completed as failed during teardown so a timed-out send cannot remain logically active.
- Wi-Fi Aware removes each pending send deferred in a finally block, covering successful delivery, callback failure, timeout, transport stop and caller cancellation.
- Nearby Connections connect waiters now use put-if-absent so concurrent callers share one connection attempt instead of replacing each other's completion signal. A timed-out or cancelled owner removes its waiter and disconnects the unfinished endpoint.
- Nearby application sends and handshake sends now rethrow coroutine cancellation rather than reporting cancellation as a normal radio failure.
- Wi-Fi Direct socket sends now preserve coroutine cancellation while still logging ordinary socket failures.
- Automated Android CI is required to validate compilation and JVM tests. Device-level reconnection, permission-revocation and radio-toggle acceptance remain outstanding.
