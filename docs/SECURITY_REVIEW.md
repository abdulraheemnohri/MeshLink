# MeshLink Security Review

Last reviewed: 2026-11-17

This is a source-level protocol inventory, not a penetration test or formal cryptographic audit. Packet signatures authenticate the sender where packet verification is performed; encryption and authentication scope differ by packet type. Do not describe all MeshLink traffic as end-to-end forward-secret.

## Pairwise ephemeral sessions

**Status: PARTIALLY INTEGRATED — private text and file offer/chunk payloads use ephemeral session keys.**

The versioned `EphemeralSessionHandshake` performs P-256 ephemeral ECDH authenticated by long-term ECDSA identity keys. The signed offer/response bind peer IDs, session ID, ephemeral public keys and 32-byte nonces. Both peers derive a transcript hash and HKDF-SHA-256 traffic-key schedule with distinct initiator-to-responder and responder-to-initiator AES-256-GCM keys.

- `SESSION_OFFER` and `SESSION_RESPONSE` are signed control packets with a 90-second handshake expiry.
- Session traffic keys are process-local and expire after 15 minutes. Replaced/expired key byte arrays are zeroed.
- Private `MESSAGE` and direct `EMERGENCY_BEACON` packets use the `MLSE1:` envelope and the directional session key.
- Messages waiting for session negotiation are stored as `WAITING_SESSION`. On successful negotiation, pending private text messages are retried automatically when a direct endpoint is available.
- `FILE_OFFER` and `FILE_CHUNK` are encrypted with the ephemeral session key by the sender. A paused outgoing transfer is resumed when a session is established.
- The session is not persisted. A process restart requires a fresh handshake; an expired session is renegotiated when a later send attempts to use it. There is no proactive timer-based rekey or persistent handshake state.
- Inbound file packets still accept the legacy long-term ECDH envelope as a compatibility fallback. That fallback must be removed only after protocol-version compatibility and upgrade tests exist.

## Packet encryption matrix

| Packet family | Current payload protection | Important limitation |
|---|---|---|
| `MESSAGE` direct private text | Ephemeral session AES-256-GCM (`MLSE1`) | Direct endpoint required; session keys are memory-only |
| Direct `EMERGENCY_BEACON` | Ephemeral session AES-256-GCM when sent through private-message path | Separate broadcast path differs; see below |
| Emergency broadcast to broadcast ID | Signed packet, payload is not encrypted | Broadcast text and optional coordinates are visible to relays/nearby receivers |
| Emergency per-peer copy | Legacy peer ECDH-derived AES-GCM | Not forward-secret |
| `FILE_OFFER`, `FILE_CHUNK` | Sender uses ephemeral session AES-GCM | Receiver retains legacy long-term ECDH compatibility; file ACKs use legacy peer encryption |
| `GROUP_MESSAGE` | Shared group AES-GCM key | All current group members share the same group key; not pairwise encryption |
| `GROUP_INVITE` | Legacy peer ECDH-derived AES-GCM | Uses long-term peer agreement key |
| `GROUP_KEY_UPDATE` | Legacy peer ECDH-derived AES-GCM | Durable per-recipient ACK/retry exists; not forward-secret |
| `GROUP_MEMBER_ROLE_UPDATE` | Legacy peer ECDH-derived AES-GCM | Signed role changes are applied, but sender-side per-recipient role ACK state is not persisted |
| `VOICE_PACKET` | Legacy peer ECDH-derived AES-GCM | Not forward-secret |
| Direct `LOCATION_UPDATE` | Legacy peer ECDH-derived AES-GCM | Broadcast location payloads are not encrypted |
| `DELIVERY_ACK` | Signed outer packet; small ACK JSON payload is not encrypted | No message content is carried in the ACK |

Legacy peer encryption derives AES-GCM keys from long-lived identity agreement keys and a ratchet counter. It provides confidentiality against passive observers but does not provide the forward secrecy of the ephemeral session protocol.

## Group membership and key rotation

**Status: PARTIALLY IMPLEMENTED — key update state is durable; role distribution recovery is incomplete.**

- Group key updates are recorded per intended peer in Room with epoch, packet ID, status, update timestamp, acknowledgement timestamp and optional revoked peer ID.
- A key-update acknowledgement is accepted only when its sender matches the intended recipient recorded for that packet.
- Group message sending is blocked while the local database contains unresolved key updates for the active group epoch.
- Admins can retry unresolved updates for the current epoch. Older epochs are marked superseded.
- A receiver accepts a key update only from a current group admin and only for a newer valid epoch.
- Role updates are signed and encrypted to each peer, and receivers validate that the sender is a current admin, the epoch matches and the timestamp is newer. However, there is no durable per-recipient role-update outbox/ACK table or replayable role-distribution recovery state.
- Group key rotation and member removal need a stronger all-member commit state machine. The current block-on-unresolved-key-update behavior is a safety guard, not proof of synchronized membership across disconnected devices.

Public/legacy group QR invitations containing a shared group key remain disabled. New invitations must be recipient-specific, encrypted to the verified recipient key, time-bounded and replay-protected.

## Identity and packet authenticity

- Peer identity imports are untrusted until the user verifies them; an existing trusted identity is not silently replaced when its public keys change.
- Packet signature and integrity-hash verification is required before normal packet delivery/forwarding paths.
- Replay guards are applied to session offers and general packet IDs. Replay persistence and retention limits should be reviewed alongside database and multi-hop tests.
- Android Keystore protects local private identity key material. This does not protect against a compromised/unlocked device or a malicious OS.

## File-transfer safety

- Offers validate size, chunk count and SHA-256 syntax before transfer creation.
- Chunks validate sender/transfer binding, index, encoded size, decoded byte length and per-chunk hash.
- Final assembled file length and SHA-256 are verified before the transfer is marked `VERIFIED`.
- Verified-file replay recovery only re-acknowledges byte-identical chunks and does not overwrite the verified destination.
- Transfer ACK waiters are in-memory and recreated after reconnect; durable transfer/chunk state supports recovery, but the end-to-end ACK/retry protocol needs multi-device kill/restart testing.

## Remaining security gates

1. Add persistent, acknowledged group role-update distribution and offline recovery.
2. Define a versioned packet-encryption policy and migrate voice, location, group control and ACK traffic where appropriate.
3. Decide and test a safe confidentiality policy for emergency broadcast payloads; do not imply broadcast payloads are encrypted.
4. Add proactive session rekey, session expiry/reconnect and cross-version interoperability tests.
5. Remove legacy file receive decryption only after a compatibility window and upgrade tests.
6. Add two-device cryptographic interoperability tests and three-device relay tests.
7. Verify replay-store persistence, clock-skew behavior, packet TTL and deduplication under process restart.
8. Complete an independent security review before production claims.
