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
| ML-07 | Session negotiation | A SESSION_OFFER and SESSION_RESPONSE complete; both sides derive opposite directional keys; tampered or replayed offers are rejected. |
| ML-08 | Automatic private-message retry | Send while a direct peer session is being negotiated; the message remains in WAITING_SESSION and is sent once the session is established. Restart during negotiation and verify the Room-backed message remains available. |
| ML-09 | Session expiry | Wait for the short-lived session to expire; a new message triggers a fresh handshake and old keys are not reused. |
| ML-10 | Encrypted file transfer | Transfer a small file and a file larger than several chunks; pause/resume; verify final SHA-256; confirm FILE_OFFER and FILE_CHUNK use MLSE1 session encryption. |
| ML-11 | Interrupted file transfer | Disable the active radio mid-transfer, restore connectivity, renegotiate a session, resume, and verify no chunk is marked complete without a valid ACK and hash. |
| ML-12 | Group key rotation | Rotate a group key; each remaining member applies the new epoch and returns an ACK; sender persists each recipient's ACK after app restart. |
| ML-13 | Group message gate | Group messages remain blocked while any remaining member has not acknowledged the active key epoch; sending resumes after the final valid ACK. |
| ML-14 | Group member removal | Remove a member, rotate the key, verify the removed member cannot decrypt subsequent group traffic. |
| ML-15 | Group role synchronization | Promote/demote a member; all connected members apply the signed role update; stale updates and attempts to demote the last admin are rejected. |
| ML-16 | Three-node relay | A sends to C through B with the selected store-and-forward strategy; duplicates are suppressed and delivery ACK reaches A. |
| ML-17 | Battery/background | Screen off and battery saver conditions are recorded; foreground-service notification and user-selected relay settings behave as configured. |

## Nearby Connections status

Google Nearby Connections is not yet registered as a MeshTransport adapter. It must be implemented as an optional adapter with explicit Play services availability checks, lifecycle-safe advertising/discovery, authenticated connection bootstrap, payload framing, and fallback to LAN/Bluetooth transports. Do not report Nearby as supported until ML-18 is passed on real devices.

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

Do not mark the release as multi-device verified until ML-01 through ML-18 have been executed on physical devices. Tests involving NFC require an NFC-capable device and a writable NDEF tag; Nearby requires Google Play services on both devices.
