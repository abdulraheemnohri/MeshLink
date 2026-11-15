# MeshLink implementation and release status

This document separates implemented source code from capabilities that have been verified on real Android devices. A class or UI screen is not evidence that a transport or mesh route works end to end.

## Latest source-level implementation (not yet device-verified)

| Area | Current source state | Remaining verification / limitation |
|---|---|---|
| BLE | MTU-aware GATT writes, bounded application fragmentation/reassembly, per-transfer aggregate byte limits, bounded peer caches, sequential write callbacks and codec unit tests | Retry policy, peripheral/central role coverage, real-device throughput and long-transfer stability |
| Wi-Fi Aware | Bounded fragmentation/reassembly and per-frame framework send callbacks | Framework send success is not remote application delivery; test NAN availability, peer-handle lifecycle and packet-level ACK behavior on supported devices |
| Wi-Fi Direct | Group-owner/client role tracking; periodic best-effort client IP lookup from ARP; TCP acceptance response through the LAN listener | ARP access and DHCP mapping are OEM-dependent; prove both directions on physical phones |
| Local LAN | Length-bounded TCP frames, socket timeouts, private-IPv4 peer filtering, bounded concurrent clients, per-peer rate limits and receiver acceptance byte | Validate interface routing on Wi-Fi, hotspot and Wi-Fi Direct |
| File transfer | Chunk hashes, receiver chunk ACKs, resumable incomplete-chunk reads, final size/SHA-256 verification, bounded file size and sanitized names | Automated integration tests, restart tests, duplicate/reordered chunk tests and two-device validation |
| Peer identity | Signed first handshake, trust-on-first-use key consistency checks, unknown non-handshake packet rejection and bidirectional endpoint mapping | QR fingerprint verification remains necessary to authenticate identity out of band; static ECDH is not forward-secret |
| Routing | Bounded in-memory replay cache, expiry pruning, persisted remaining Spray-and-Wait budget and route-aware queue flushing | Cache is not durable across process restart; physical three-node relay tests and queue policy tests remain required |

These entries describe source changes only. They do not mean the corresponding radio path has passed a physical-device acceptance test. Use docs/DEVICE_TEST_CHECKLIST.md to record the two-device and three-device acceptance runs.

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
