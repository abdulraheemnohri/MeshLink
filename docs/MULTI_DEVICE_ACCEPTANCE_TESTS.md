# MeshLink multi-device acceptance plan

This plan is the release gate for transport, security, file-transfer, and group-sync changes. Unit tests do not replace physical radio testing.

## Test setup

- Device A: Android 17 / Pixel 6 (primary reference device).
- Device B: a second Android phone with Wi-Fi and Bluetooth.
- Device C: optional third phone for relay and multi-hop tests.
- Install the same debug build on all devices. For upgrade tests, use the same signing key.
- Import each device's identity QR on the other devices, compare fingerprints out of band, then explicitly mark the peers trusted.
- Enable one transport at a time before enabling multiple transports.

## Acceptance scenarios

| ID | Scenario | Pass criteria |
|---|---|---|
| ML-01 | QR identity import | Correct device ID and fingerprint are shown; malformed QR is rejected; no identity is trusted automatically. |
| ML-02 | NFC identity handoff | A valid MeshLink NDEF URI imports the same identity as QR; unrelated NDEF payloads are ignored. |
| ML-03 | LAN discovery | A and B discover each other on the same WLAN and connect without internet access. |
| ML-04 | Bluetooth LE discovery | A and B discover each other over BLE; a failed BLE path does not disable LAN. |
| ML-05 | Bluetooth Classic | RFCOMM handshake authenticates the expected peer before application packets are accepted. |
| ML-06 | Wi-Fi Direct / Aware | Supported devices discover and exchange packets; unsupported hardware reports unavailable rather than claiming success. |
| ML-07 | Session negotiation | A SESSION_OFFER and SESSION_RESPONSE complete; both sides derive opposite directional keys; tampered or replayed offers are rejected. Repeat with both devices initiating simultaneously and verify the deterministic session-ID arbitration leaves matching directional keys. |
| ML-08 | Automatic private-message retry | Send while a direct peer session is being negotiated; the message remains in WAITING_SESSION and is sent once the session is established. Restart during negotiation and verify the Room-backed message remains available. |
| ML-09 | Session rekey and expiry | Verify automatic rekey begins 60 seconds before the 15-minute lifetime, simultaneous offers converge on one session, old keys are cleared, and a packet-loss/reconnect during rekey recovers without mismatched keys. |
| ML-10 | Encrypted file transfer | Transfer a small file and a file larger than several chunks; pause/resume; verify final SHA-256; confirm FILE_OFFER and FILE_CHUNK use MLSE1 session encryption. |
| ML-11 | Interrupted file transfer | Disable the active radio mid-transfer, restore connectivity, renegotiate a session, resume, and verify no chunk is marked complete without a valid ACK and hash. |
| ML-12 | Group key rotation | Rotate a group key; each remaining member applies the new epoch and returns an ACK; sender persists each recipient's ACK after app restart. |
| ML-13 | Group message gate | Group messages remain blocked while any remaining member has not acknowledged the active key epoch; sending resumes after the final valid ACK. |
| ML-14 | Group member removal | Remove a member, rotate the key, verify the removed member cannot decrypt subsequent group traffic. |
| ML-15 | Group role synchronization | Promote/demote a member; every recipient applies the signed role update and sends an ACK; the sender persists per-recipient ACK state; a lost ACK is recovered by an idempotent retry after reconnect; stale updates and attempts to demote the last admin are rejected. |
| ML-16 | Three-node relay | A sends to C while no direct A-C path exists; B forwards without decrypting payload; C receives once; ACK returns to A; TTL is decremented and duplicate loops are rejected. |
| ML-17 | Battery/background | Screen off and battery saver conditions are recorded; foreground-service notification and user-selected relay settings behave as configured. |

## Nearby Connections status

Google Nearby Connections is registered as an optional MeshTransport adapter. The source includes Play services availability checks, P2P_CLUSTER advertising/discovery, signed identity bootstrap with nonce proof, byte payload framing, and explicit relay lifecycle control. The adapter is implemented in source but remains unverified on physical devices; do not describe it as device-validated until ML-18 passes.

| ID | Scenario | Pass criteria |
|---|---|---|
| ML-18 | Nearby Connections | A and B discover, authenticate, connect, exchange framed MeshPackets, and recover to another enabled transport when Nearby disconnects. |

## Release evidence to collect

For each scenario record:
- app version, commit SHA, Android version, device model;
- enabled transport(s), distance, WLAN topology, and internet availability;
- test start/end time and pass/fail result;
- relevant exported diagnostics and security/network event logs;
- file size and expected/actual SHA-256 for transfer tests.

Do not mark the release as multi-device verified until ML-01 through ML-21 have been executed on physical devices. Tests involving NFC require an NFC-capable device and a writable NDEF tag; Nearby requires Google Play services on both devices.


| ML-19 | Durable role update process recovery | Kill the sender after recording a role update but before ACK; restart, reconnect the intended recipient and verify the update is retried and ACKed without losing the current role. |
| ML-20 | Relay loop and TTL abuse | Inject a packet whose visited path already includes the relay, a destination already in the path, and a packet with hopLimit=1; none may be forwarded. |
| ML-21 | Session process-death recovery | Kill one peer during SESSION_OFFER/RESPONSE; after restart the old ephemeral state is not reused, a fresh session is negotiated and queued private text/file transfer resumes safely. |

## Physical transport evidence

Use [TRANSPORT_ACCEPTANCE_RESULTS.md](TRANSPORT_ACCEPTANCE_RESULTS.md) to record independent LAN, BLE, Bluetooth Classic, Wi-Fi Direct, Wi-Fi Aware and Nearby results. All physical rows currently remain NOT RUN. A successful emulator test or CI build does not change that status.

