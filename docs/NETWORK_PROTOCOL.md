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
