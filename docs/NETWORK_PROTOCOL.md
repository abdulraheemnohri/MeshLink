# MeshLink Protocol (MLP/1)

MeshLink Protocol defines a transport-independent envelope for all offline mesh packets.

## Packet Structure
- `protocolVersion`: Integer (e.g., 1)
- `messageId`: Unique UUID string
- `senderId`: Sender device identifier
- `destinationId`: Recipient device identifier or broadcast ID
- `packetType`: HELLO, PEER_DISCOVERY, PEER_HANDSHAKE, MESSAGE, GROUP_MESSAGE, FILE_OFFER, FILE_CHUNK, DELIVERY_ACK, ROUTE_UPDATE, VOICE_PACKET, LOCATION_UPDATE, DISCONNECT, GROUP_INVITE, EMERGENCY_BEACON, BRIDGE_SYNC
- `createdAt`: Timestamp
- `expiresAt`: Expiration timestamp for DTN store-and-forward
- `hopLimit`: TTL hop counter
- `routingStrategy`: AUTONOMOUS, DIRECT, STORE_AND_FORWARD, EPIDEMIC, SPRAY_AND_WAIT
- `encryptedPayload`: AES-256-GCM ciphertext
- `signature`: ECDSA P-256 signature
- `integrityHash`: SHA-256 integrity hash


## Transport independence and link selection

MLP/1 packets are carried over transports; routing strategies (DIRECT, STORE_AND_FORWARD, EPIDEMIC, SPRAY_AND_WAIT, AUTONOMOUS) are not radio protocols. See [NETWORKING_MATRIX.md](NETWORKING_MATRIX.md) for same-WLAN/LAN, hotspot, Wi-Fi Direct, Wi-Fi Aware, BLE, NFC, QR, Bluetooth Classic, Nearby Connections and gateway support boundaries.

Current adapters include BLE, Wi-Fi Aware, Wi-Fi Direct and Local LAN. Local LAN is the path for compatible same-network WLAN and hotspot subnets. NFC/QR are bootstrap methods; Bluetooth Classic RFCOMM and Google Nearby Connections are not currently registered data transports. A user-facing persisted Automatic / Prefer / Only / Disabled selector remains future work. Do not advertise an enum value as a functioning adapter.
