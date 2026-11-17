# MeshLink Routing Engine

MeshLink implements a best-effort Delay-Tolerant Networking (DTN) engine. The routing implementation is source-level functionality; end-to-end multi-device acceptance is tracked separately in [MULTI_DEVICE_ACCEPTANCE_TESTS.md](MULTI_DEVICE_ACCEPTANCE_TESTS.md).

## Routing strategies

1. **Direct:** transmit to the destination over a currently discovered endpoint.
2. **Store-and-forward:** persist an undelivered packet in Room until a suitable destination or relay path becomes available.
3. **Epidemic:** forward to eligible unvisited peers, using packet-ID replay suppression and a bounded hop limit.
4. **Spray-and-wait:** distribute a limited copy budget and retain the remaining copies.
5. **Autonomous:** select a strategy from reachability, battery and configured policy.

## Multi-hop safety invariants

- A packet has a stable message ID, creation/expiry timestamps, hop limit, hop count and visited-peer path.
- A relay must not forward a packet if its own device ID is already in `visitedPeers`.
- A relay must not send to the previous sender or another peer already in the visited path.
- Forwarding consumes exactly one hop and appends the relay's device ID exactly once.
- A packet whose destination already appears in its visited path is not routed again.
- Packet signature, expiry and replay checks run before normal packet delivery/forwarding.
- Relays forward the signed encrypted packet envelope without decrypting application payloads.

The pure `MeshForwardingPolicy` helper enforces hop/visited-path decisions and has JVM regression tests. These tests do not replace the required three-physical-device acceptance run.

## Current limitations

- Routes are learned from discovered direct peers and observed relay behavior; there is no global topology oracle.
- Store-and-forward and routing are best-effort. Radio sleep, Android background restrictions, NAT and OEM behavior can delay or prevent delivery.
- Sequential transport fallback is supported; simultaneous multipath bonding is not.
- A delivery ACK indicates application receipt at the destination, not guaranteed human reading.
- No public internet relay, satellite gateway or external LoRa/Zigbee/Thread gateway is included.

## Acceptance

Use A-B-C topology: A must not have a direct path to C; B is the only relay. Verify packet delivery, ACK return, duplicate suppression, TTL exhaustion, relay restart and route recovery. Record the build SHA and diagnostics in [TRANSPORT_ACCEPTANCE_RESULTS.md](TRANSPORT_ACCEPTANCE_RESULTS.md).


## User-selectable chat connection modes

The private and group chat composer exposes all five routing strategies: Autonomous, Direct, Store & Forward, Epidemic, and Spray & Wait. The selected strategy is persisted in the app's default routing setting and is applied to outgoing private and group message packets. Transport selection remains a separate concern: LAN, Wi-Fi Direct, Wi-Fi Aware, BLE, Bluetooth Classic and Nearby choose the physical path, while the routing strategy governs forwarding behavior.

Autonomous delegates to the existing reachability/battery/queue-aware strategy selector. Direct targets an immediately reachable destination. Store & Forward persists packets for later contact. Epidemic forwards to eligible unvisited peers with replay suppression and hop bounds. Spray & Wait uses the configured copy budget and waits for a direct encounter.

The chat selector is a per-send routing preference shared as the app's default for subsequent chats; it does not change the configured transport adapter policy.
