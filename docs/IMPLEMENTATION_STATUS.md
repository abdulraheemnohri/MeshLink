# MeshLink implementation and release status

This document separates implemented source code from capabilities that have been verified on real Android devices. A class or UI screen is not evidence that a transport or mesh route works end to end.

## Group key update recovery

- Unresolved per-recipient group key updates are persisted in Room and can be retried from the group chat toolbar.
- Retry requires the local device to be a group admin and the intended peer to be QR-verified, unblocked, and have a usable agreement key.
- Reissued updates retain the original revoked-member identifier, so a retry after member removal does not silently lose revocation semantics.
- Older unresolved epochs are marked SUPERSEDED when a newer key epoch is distributed; only the current epoch's unresolved recipients block group messaging.
- Recipients that are missing, unverified, blocked, or not yet connected remain unresolved and keep group messaging gated until recovery succeeds.
- Database schema v7 adds persisted revocation metadata; migrations 3→4 through 6→7 are registered.

## Latest source-level implementation (not yet device-verified)

| Area | Current source state | Remaining verification / limitation |
|---|---|---|
| BLE | MTU-aware GATT writes, bounded application fragmentation/reassembly, per-transfer aggregate byte limits, bounded peer caches, sequential write callbacks and codec unit tests | Retry policy, peripheral/central role coverage, real-device throughput and long-transfer stability |
| Wi-Fi Aware | Bounded fragmentation/reassembly and per-frame framework send callbacks | Framework send success is not remote application delivery; test NAN availability, peer-handle lifecycle and packet-level ACK behavior on supported devices |
| Wi-Fi Direct | Group-owner/client role tracking; periodic best-effort client IP lookup from ARP; TCP acceptance response through the LAN listener | ARP access and DHCP mapping are OEM-dependent; prove both directions on physical phones |
| Local LAN / WLAN | Length-bounded TCP frames, UDP discovery, private-IPv4 filtering, bounded clients and per-peer rate limits; intended for same-network Wi-Fi and compatible hotspot subnets | Same-network discovery may be blocked by AP/client isolation; validate Wi-Fi, hotspot, USB tethering and Ethernet interfaces on real devices |
| NFC / QR | NFC URI intent import; QR identity display, manual URI import and offline CameraX + ZXing QR scanner | NFC tag write/read handoff still needs physical testing; QR is bootstrap only, not a continuous network transport |
| Bluetooth Classic / Nearby Connections | Bluetooth Classic RFCOMM adapter is registered; explicit connect uses paired devices and authenticated identity handshake | RFCOMM still needs two-device acceptance testing; Google Nearby Connections adapter is not implemented |
| File transfer | Chunk hashes, receiver chunk ACKs, resumable incomplete-chunk reads, final size/SHA-256 verification, bounded file size and sanitized names | Automated integration tests, restart tests, duplicate/reordered chunk tests and two-device validation |
| Peer identity | Signed first handshake, trust-on-first-use key consistency checks, unknown non-handshake packet rejection and bidirectional endpoint mapping | QR fingerprint verification remains necessary to authenticate identity out of band; static ECDH is not forward-secret |
| Routing | Bounded SharedPreferences-backed durable packet replay store, expiry pruning, persisted remaining Spray-and-Wait budget and route-aware queue flushing | Physical three-node relay tests, queue policy tests and storage-failure behavior remain required |

These entries describe source changes only. They do not mean the corresponding radio path has passed a physical-device acceptance test. Use docs/DEVICE_TEST_CHECKLIST.md to record the two-device and three-device acceptance runs.

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
