# MeshLink Security Review

Last reviewed: 2026-11-17

This is a source-level protocol inventory, not a penetration test or formal cryptographic audit. Packet signatures authenticate the sender where packet verification is performed; encryption and authentication scope differ by packet type. Do not describe all MeshLink traffic as end-to-end forward-secret.

## Pairwise ephemeral sessions

**Status: PARTIALLY INTEGRATED — private text and file offer/chunk payloads use ephemeral session keys.**

The versioned `EphemeralSessionHandshake` performs P-256 ephemeral ECDH authenticated by long-term ECDSA identity keys. The signed offer/response bind peer IDs, session ID, ephemeral public keys and 32-byte nonces. Both peers derive a transcript hash and HKDF-SHA-256 traffic-key schedule with distinct initiator-to-responder and responder-to-initiator AES-256-GCM keys.

- `SESSION_OFFER` and `SESSION_RESPONSE` are signed control packets with a 90-second handshake expiry.
- Session traffic keys are process-local and expire after 15 minutes. Replaced/expired key byte arrays are zeroed. A maintenance loop starts rekey negotiation 60 seconds before expiry for live peers, and verified peer discovery/reconnection triggers session establishment.
- Private `MESSAGE` and direct `EMERGENCY_BEACON` packets use the `MLSE1:` envelope and the directional session key.
- Messages waiting for session negotiation are stored as `WAITING_SESSION`. On successful negotiation, pending private text messages are retried automatically when a direct endpoint is available.
- `FILE_OFFER` and `FILE_CHUNK` are encrypted with the ephemeral session key by the sender. A paused outgoing transfer is resumed when a session is established.
- The session is not persisted. A process restart requires a fresh handshake; a maintenance loop proactively rekeys live sessions before expiry. Concurrent offers use a deterministic session-ID tie-break. Pending handshake state remains memory-only.
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
- Role updates are signed and encrypted to each peer, and receivers validate that the sender is a current admin, the epoch matches and the timestamp is newer. The local role change and batch creation of all per-recipient outbox records occur in one Room transaction before network dispatch. Schema version 9 stores packet ID, recipient, target member, role, issue time, status and ACK timestamp. Delivery ACKs are accepted only from the intended recipient; unresolved updates are retried after peer discovery/reconnection. Exact duplicate role updates are idempotently accepted so a retry can recover a lost ACK.
- Group key rotation and member removal still need a stronger all-member commit state machine. The block-on-unresolved-key-or-role-update behavior is a safety guard, not proof of synchronized membership across disconnected devices. Role-update retries and ACKs are now durable, but still need lost-ACK and offline-member device tests.

Public/legacy group QR invitations containing a shared group key remain disabled. New invitations must be recipient-specific, encrypted to the verified recipient key, time-bounded and replay-protected.

## Identity and packet authenticity

- Peer identity imports are untrusted until the user verifies them; an existing trusted identity is not silently replaced when its public keys change.
- Packet signature and integrity-hash verification is required before normal packet delivery/forwarding paths.
- Replay guards are applied to session offers and general packet IDs. Replay persistence and retention limits should be reviewed alongside database and multi-hop tests.
- Android Keystore protects local private identity key material. This does not protect against a compromised/unlocked device or a malicious OS.

## Mutable routing metadata integrity

**Status: OPEN — routing metadata is bounded but not cryptographically attested per hop.**

The current canonical packet signature/integrity string binds protocol version, message ID, sender, destination, packet type, creation/expiry timestamps, routing strategy and encrypted payload. It intentionally excludes mutable relay fields: hop limit, hop count, visited-peer list, spray-copy budget, priority and source-cluster metadata.

The routing layer now bounds hop counters and visited-path size, rejects duplicate path entries, prevents forwarding to a previous/visited hop and drops packets whose destination is already in the path. These checks protect honest nodes from malformed metadata and common loops. They do not prove that a malicious relay preserved the path or hop budget: an intermediary can alter excluded fields without invalidating the origin signature. Treat visited-peer lists as routing hints, not authenticated provenance.

A future protocol version should add authenticated per-hop route attestations or another reviewed mutable-envelope design before claiming protection against malicious relay tampering. Keep the replay cache and absolute packet expiry as independent limits.

## File-transfer safety

- Offers validate size, chunk count and SHA-256 syntax before transfer creation.
- Chunks validate sender/transfer binding, index, encoded size, decoded byte length and per-chunk hash.
- Final assembled file length and SHA-256 are verified before the transfer is marked `VERIFIED`.
- Verified-file replay recovery only re-acknowledges byte-identical chunks and does not overwrite the verified destination.
- Transfer ACK waiters are in-memory and recreated after reconnect; durable transfer/chunk state supports recovery, but the end-to-end ACK/retry protocol needs multi-device kill/restart testing.

## Remaining security gates

1. Add an explicit all-member group epoch commit/recovery state machine and test the new durable role-update outbox under lost ACKs and offline reconnects.
2. Define a versioned packet-encryption policy and migrate voice, location, group control and ACK traffic where appropriate.
3. Decide and test a safe confidentiality policy for emergency broadcast payloads; do not imply broadcast payloads are encrypted.
4. Validate proactive session rekey, session expiry/reconnect, simultaneous-offer arbitration and cross-version interoperability on two physical devices.
5. Remove legacy file receive decryption only after a compatibility window and upgrade tests.
6. Add authenticated per-hop route attestations in a versioned protocol, then run two-device cryptographic interoperability and three-device relay tests.
7. Verify replay-store persistence, clock-skew behavior, packet TTL and deduplication under process restart.
8. Complete an independent security review before production claims.
