# MeshLink Protocol (MLP/1)

MLP/1 defines a transport-independent packet envelope. The same signed packet can be carried by a supported local transport; routing strategy is not a radio protocol.

## Packet fields

| Field | Meaning |
|---|---|
| `protocolVersion` | Protocol version integer |
| `messageId` | Unique packet UUID used for deduplication and ACK correlation |
| `senderId` | Stable MeshLink device identity |
| `destinationId` | Peer/group ID or `MESH_BROADCAST` |
| `packetType` | Application/control packet kind |
| `createdAt`, `expiresAt` | Packet freshness and DTN expiry timestamps |
| `hopLimit`, `hopCount` | Remaining relay budget and traversed hop count |
| `routingStrategy` | DIRECT, STORE_AND_FORWARD, EPIDEMIC, SPRAY_AND_WAIT or AUTONOMOUS |
| `encryptedPayload` | Packet-type-specific protected payload or explicit bootstrap/control payload |
| `signature` | ECDSA P-256 sender signature |
| `integrityHash` | SHA-256 integrity hash over canonical signed fields |
| `visitedPeers` | Device IDs already traversed; used to prevent loops |
| `sprayCopies` | Remaining Spray-and-Wait copy budget |
| `priority` | Scheduling priority |
| `sourceCluster` | Optional bridge/cluster metadata |

## Packet types

The current enum includes peer discovery/handshake, `SESSION_OFFER`, `SESSION_RESPONSE`, private `MESSAGE`, `GROUP_MESSAGE`, `GROUP_INVITE`, `GROUP_KEY_UPDATE`, `GROUP_MEMBER_ROLE_UPDATE`, `FILE_OFFER`, `FILE_CHUNK`, `DELIVERY_ACK`, `ROUTE_UPDATE`, `VOICE_PACKET`, `LOCATION_UPDATE`, `EMERGENCY_BEACON`, `DISCONNECT` and `BRIDGE_SYNC`.

## Security envelope

The outer packet is signed by the sender identity and verified against the pinned signing key before normal delivery/relay. The payload encryption depends on packet type; not every payload is AES-GCM ciphertext and not every packet uses ephemeral session keys.

- Private direct text and direct emergency-message copies use the ephemeral `MLSE1:` session envelope.
- File offers and chunks use ephemeral session encryption when sent by the current sender implementation; inbound legacy peer-ECDH decoding remains for compatibility, and file ACKs still use legacy peer encryption.
- Group messages use the current shared group key.
- Group control, voice and direct location traffic still use legacy peer ECDH-derived encryption.
- Broadcast emergency payloads are not encrypted; their contents can be read by relays/receivers.
- Small delivery ACK payloads are signed but not encrypted.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for the maintained packet encryption matrix and open security gates.

## Routing invariants

- Reject expired packets and replayed message IDs.
- Do not forward a packet to its previous sender or a peer already in `visitedPeers`.
- Do not relay a packet when the local node is already in its visited path.
- Do not route a packet whose destination already appears in its visited path.
- Each relay consumes one hop, increments `hopCount`, and appends its own device ID.
- Relays forward the encrypted/signed packet envelope without reading application plaintext.
- Respect payload limits and the selected transport policy; endpoint locators are adapter-specific.
- Route metadata is bounded: hop counters and visited-path length are limited to 32, duplicate visited IDs are rejected, and `hopLimit + hopCount` cannot exceed 32.

**Security limitation:** the current canonical origin signature does not cover mutable relay fields (`hopLimit`, `hopCount`, `visitedPeers`, `sprayCopies`, `priority`, `sourceCluster`). Relays can update them, but this also means a malicious relay can tamper with them without invalidating the origin signature. Visited paths are best-effort routing hints, not authenticated provenance. See [SECURITY_REVIEW.md](SECURITY_REVIEW.md); authenticated per-hop route attestations remain an open protocol-version upgrade.

## Transport independence

Current source adapters include Local LAN, BLE, Bluetooth Classic RFCOMM, Wi-Fi Direct, Wi-Fi Aware and optional Google Nearby Connections. NFC and QR are identity/bootstrap methods, not continuous packet transports. Internet/cellular/satellite relay gateways and LoRa/Zigbee/Thread adapters are not included.

See [NETWORKING_MATRIX.md](NETWORKING_MATRIX.md) for platform requirements and [TRANSPORT_ACCEPTANCE_RESULTS.md](TRANSPORT_ACCEPTANCE_RESULTS.md) for physical-device test status.
