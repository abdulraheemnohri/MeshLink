# MeshLink test plan

## Automated test layers

1. Protocol codec tests: round-trip encoding, unknown versions, invalid enum values, malformed JSON/protobuf, oversized payloads and canonical-signature stability.
2. Routing tests: TTL expiry, hop decrement, visited-node loop prevention, duplicate IDs, spray-copy accounting, queue persistence and priority ordering.
3. Crypto tests: signature tampering, wrong recipient, modified associated data, invalid nonce/tag, malformed public keys and identity fingerprint stability.
4. Transfer tests: chunk ordering, missing chunks, duplicate chunks, wrong checksum, interrupted transfer and bounded reassembly.
5. Repository tests: message persistence, pending queue recovery, expiry cleanup and migration tests.
6. Android instrumentation: runtime permissions, foreground service start/stop, Bluetooth and Wi-Fi capability absence, lifecycle recreation.

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
