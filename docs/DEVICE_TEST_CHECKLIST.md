# MeshLink physical-device acceptance checklist

CI verifies compilation and automated tests. It does not prove that Android radios interoperate or that multi-hop delivery works. Complete this checklist on real devices before calling a transport release-ready.

## Test setup

- Use at least two physical Android phones for direct transport tests.
- Use three phones (A, B and C) for relay/store-and-forward tests.
- Install the same MeshLink build on every test phone.
- Record the Git commit SHA and Android build number for each phone.
- Keep devices close together for initial tests; repeat at realistic distances.
- Enable the relevant radio and grant only the permissions MeshLink requests.
- Exchange and verify QR identity fingerprints through a trusted channel before sensitive tests.
- Do not use real emergency incidents or sensitive personal information as test payloads.

## Build under test

| Field | Value |
|---|---|
| Git commit SHA | |
| GitHub Actions run | |
| APK version | |
| Test date/time | |
| Tester | |

## Device inventory

| Device | Manufacturer/model | Android version/build | RAM | Battery start/end |
|---|---|---|---|---|
| A | | | | |
| B | | | | |
| C | | | | |

## Direct transport tests

For every transport, send a short text message, a message larger than one transport frame, and a file with a known SHA-256 digest. Repeat in both directions.

| Transport | Test | Expected result | Result / notes |
|---|---|---|---|
| BLE | Peer discovery | Correct peer appears; MAC is not treated as identity | |
| BLE | Fragmented message | Exact message received once | |
| BLE | File transfer | File hash matches sender | |
| BLE | Disconnect during transfer | Transfer pauses/fails safely and can resume | |
| Wi-Fi Aware | NAN discovery | Both peers discover each other on supported hardware | |
| Wi-Fi Aware | Fragmented message | Complete packet reassembled exactly once | |
| Wi-Fi Aware | Peer leaves range | No crash; pending sends time out | |
| Wi-Fi Direct | Client to group owner | Message received and acknowledged | |
| Wi-Fi Direct | Group owner to client | Correct client address found and message received | |
| Wi-Fi Direct | Group recreated | Old client addresses are discarded | |
| Local LAN | Same Wi-Fi network | Message received and acknowledged | |
| Local LAN | Hotspot network | Interface discovery and TCP delivery work | |
| Local LAN | Malformed/oversized frame | Rejected without app crash or excessive memory use | |

## Routing and recovery tests

| Test | Procedure | Expected result | Result / notes |
|---|---|---|---|
| Three-node relay | A sends to C while only B can reach both | C receives only through B | |
| Store and forward | Turn C off; send A-to-C through B; turn C on | B forwards pending packet when C returns | |
| Duplicate suppression | Deliver same packet ID repeatedly | Application shows one message | |
| Expiry | Let a packet expire before C reconnects | Expired packet is discarded | |
| Hop limit | Configure a low hop limit and relay through nodes | Packet is not forwarded after hop budget ends | |
| Process restart | Queue a message, force-stop and reopen app | Persisted queue state is recovered | |
| Screen off | Repeat direct and relay tests with screen off | Behavior and OS restrictions are recorded | |
| Battery saver | Repeat with battery saver enabled | No crash; limitations are documented | |

## Security checks

- Confirm that a changed public key for an existing peer identity is rejected.
- Confirm that an unknown peer cannot send application packets before a valid handshake.
- Confirm that a modified packet signature is rejected.
- Confirm that a replayed packet ID is not delivered twice.
- Confirm that no plaintext, private key or session key appears in logs.
- Verify QR fingerprints out of band before marking a peer trusted.
- Record any unexpected identity changes or trust prompts as a release blocker.

## Results and release decision

For each test, use only: PASS, FAIL, BLOCKED or NOT TESTED.

A release is not transport-verified if any mandatory row is NOT TESTED or BLOCKED. A failed security check is a release blocker. Attach sanitized logs and the exact commit SHA to reproducible bug reports; remove names, message contents, QR payloads, keys and location data.

MeshLink is best-effort offline communication. Do not rely on it for guaranteed emergency dispatch, medical care or public-safety coordination.
