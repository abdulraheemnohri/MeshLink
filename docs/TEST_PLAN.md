# MeshLink test plan

## Automated test layers

1. Protocol codec tests: round-trip encoding, unknown versions, invalid enum values, malformed JSON/protobuf, oversized payloads and canonical-signature stability.
2. Routing tests: TTL expiry, hop decrement, visited-node loop prevention, duplicate IDs, spray-copy accounting, queue persistence and priority ordering.
3. Crypto tests: signature tampering, wrong recipient, modified associated data, invalid nonce/tag, malformed public keys and identity fingerprint stability.
4. Transfer tests: chunk ordering, missing chunks, duplicate chunks, wrong checksum, interrupted transfer and bounded reassembly.
5. Repository tests: message persistence, pending queue recovery, expiry cleanup and migration tests.
6. Android instrumentation: runtime permissions, foreground service start/stop, Bluetooth and Wi-Fi capability absence, lifecycle recreation.

## New reliability regression cases

- BLE frame codec: out-of-order frames, incomplete transfers, duplicate frames, malformed headers, maximum size and transfer timeout refresh.
- Wi-Fi Aware: callback success/failure, callback timeout, stop while sends are pending, peer-handle loss and fragmented packet reassembly.
- TCP transport: reject non-private IPv4 peers, reject oversized frames, return a negative acceptance byte for malformed packets and positive acceptance only after packet decoding.
- File transfer: receiver ACK for offer/chunks, no local completion on transport-write alone, pause/resume from incomplete chunks, duplicate chunk idempotency, invalid chunk index, path traversal filenames, zero-byte files, final size mismatch and final SHA-256 mismatch.
- Peer identity: reject unsigned handshakes, reject changed keys for an existing identity and reject non-handshake packets from unknown peers.
- Spray-and-Wait: verify that the durable queue stores only the remaining copy budget after spraying.

## Test invariants

- No untrusted packet can allocate memory based on an unchecked length field.
- A packet is delivered to the application at most once per message ID.
- A relay never decrypts a direct message addressed to another peer.
- Every forwarded packet has a strictly decreasing hop budget.
- Expired packets are never forwarded.
- Transport failure must not delete a durable queued message.
- A transfer is complete only after size and cryptographic digest verification.

## Device lab

Use at least two physical devices for direct transports and three for a real multi-hop test. Record Android version, manufacturer, transport, negotiated MTU, connection duration, throughput, retries, battery impact and screen state. Emulator-only success is not a mesh-network acceptance test.


## Camera QR scanner acceptance

- Open Identity → Scan QR with Camera and confirm CAMERA permission is requested only at that point.
- Deny permission and confirm manual URI import and local QR display remain available.
- Grant permission and scan a valid MeshLink identity QR while offline.
- Confirm malformed, unrelated, oversized and self-identity payloads are rejected by the existing decoder/import checks.
- Dismiss the scanner and confirm the camera is released.
- Rotate the device, test low light/autofocus and verify the analyzer does not crash or retain camera resources.
- Confirm no captured image or scan history is written to app storage.
