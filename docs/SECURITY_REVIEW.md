# MeshLink Security Review

Last reviewed: 2026-11-17

This is a source-level review, not a penetration test or formal cryptographic audit. A successful Android CI build does not establish that the network protocol is secure in deployment.

## Pairwise encryption and forward secrecy

**Status: OPEN — legacy packet encryption still uses long-lived identity ECDH keys.**

A new EphemeralSessionHandshake primitive implements a versioned P-256 ephemeral ECDH exchange authenticated by long-term ECDSA identity keys. The offer and response bind both peer IDs, a random session ID, ephemeral public keys and 32-byte nonces. Both sides derive a transcript hash and HKDF-SHA-256 traffic-key schedule with distinct initiator-to-responder and responder-to-initiator keys. It rejects unsupported versions, peer-ID substitution, malformed nonce/key encodings and invalid signatures.

**Important limitation:** this is a tested protocol building block, not the active packet encryption path. MeshRoutingEngine, file transfer and store-and-forward queues still use legacy encryptForPeer/decryptFromPeer APIs. Therefore MeshLink does not yet provide forward secrecy for application traffic. Do not advertise it as enabled. Replay persistence/deduplication for handshake session IDs is also required; this primitive does not itself persist consumed offers. Routing integration requires explicit handshake packet negotiation, authenticated session state, offline queue re-encryption/retry semantics, expiry/rekey and mixed-version compatibility tests.

## Group invitation QR

**Status: MITIGATED IN APP — QR export and legacy QR import are disabled.**

Public group QR invitations are disabled because the prior URI exposed the raw group AES key. Direct peer-to-peer group invitations remain separate.

## Peer identity import

**Status: PARTIALLY MITIGATED — new imports are untrusted by default and key changes are rejected.**

Existing trusted peers retain trust only when both public keys and fingerprint match. Changed keys for the same device ID are rejected. Users must compare fingerprints out-of-band before manually trusting a new identity.

## Group key rotation

**Status: PARTIALLY IMPLEMENTED — encrypted epoch updates, admin checks and ACKs exist; reliable all-member completion is not guaranteed.**

Admins rotate group keys and distribute per-recipient encrypted updates. Receivers validate trusted admin sender, key length and epoch, then acknowledge application. Offline members may retain old keys; queued/sent is not delivered, and no all-member commit/recovery state machine exists.

## QR scanner reliability

Camera initialization/binding failures are surfaced with a retry action. Physical-device camera testing is still required.

## Remaining release gates

- Wire the ephemeral handshake into all packet encryption/decryption paths with a versioned packet protocol.
- Add persistent session state, expiration, rekey, replay handling and safe offline queue migration.
- Add two-device handshake and packet interoperability tests.
- Implement reliable group epoch acknowledgement/recovery and distributed role updates.
- Validate all transports on two- and three-device networks.
- Verify release signing, upgrade behavior and key-loss recovery.
- Obtain an independent security review.
