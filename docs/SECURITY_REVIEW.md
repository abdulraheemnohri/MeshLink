# MeshLink Security Review

Last reviewed: 2026-11-17

This is a source-level review, not a penetration test or a formal cryptographic audit. A successful Android CI build does not establish that the network protocol is secure in deployment.

## Current high-priority findings

### 1. Group invitation QR exposed the group encryption key

**Status: MITIGATED IN APP — QR export and legacy QR import are disabled. Secure recipient-specific QR invitations are not yet implemented.**

`MeshCryptoEngine.encodeGroupInviteUri()` currently places the raw group AES key in the `key` query parameter. Anyone who obtains a screenshot, copied URI, QR image, chat export, or log containing that URI can recover the group key and decrypt group traffic encrypted with that key.

The old QR invite format was a bearer secret, not a safe public invitation. The app now returns a non-secret disabled marker instead of exporting the key, rejects legacy v1 QR payloads, and replaces the QR dialog with a security notice. Direct peer-to-peer group invitations remain a separate existing feature. A safe QR replacement still requires an authenticated, per-recipient encrypted key envelope, invite expiry, explicit recipient acceptance and protocol interoperability tests.

### 2. Pairwise key agreement uses long-lived identity ECDH keys

**Status: OPEN — forward secrecy not provided.**

The current pairwise key derivation uses the long-lived local agreement private key and the peer's long-lived public agreement key. The HMAC construction is a legacy one-step derivation rather than a complete HKDF extract/expand schedule with transcript binding and directional keys. Compromise of a long-lived agreement private key can expose recorded sessions that used that key.

A future protocol version should use authenticated ephemeral ECDH, transcript-bound HKDF-SHA-256, distinct send/receive keys, explicit protocol/version context, and replay-resistant session identifiers. Do not silently change this derivation without version negotiation and compatibility tests.

### 3. QR identity import is not out-of-band identity verification

**Status: PARTIALLY MITIGATED — new imports are untrusted by default and key changes are rejected. Manual fingerprint comparison is still required.**

Importing a public identity payload now creates an untrusted peer record by default. Existing trusted peers retain their trust state only when both public keys and the fingerprint match exactly. If the same device ID arrives with changed keys or fingerprint, the import is rejected, a CRITICAL security event is recorded, and the existing pinned identity is preserved. Users must still compare the displayed fingerprint through a separate trusted channel before manually marking a new peer trusted.

### 4. Group key rotation and member removal need protocol enforcement

**Status: PARTIALLY IMPLEMENTED — encrypted epoch updates, admin checks and ACKs are wired; reliable all-member completion is not guaranteed.**

Admins can now rotate the group key or remove a member and rotate in one local database transaction. The new key and epoch are sent to remaining fingerprint-trusted members in signed, pairwise-encrypted `GROUP_KEY_UPDATE` packets. Receivers require a trusted sender who is an admin in their local group membership, validate 32-byte key material, reject stale epochs, apply revocation and send delivery ACKs. Group invitations also require a trusted peer, a valid 256-bit key, a creator ID matching the signed packet sender, and admin authorization for an existing group.

This is not a reliable all-member commit protocol: queued/sent is not the same as delivered, ACKs are asynchronous, members that are offline may retain an old key until they reconnect, and no quorum/timeout/recovery state machine exists. A removed member can retain old keys and decrypt historical content already captured. The current pairwise wrapping still uses long-lived ECDH identity keys and does not provide forward secrecy.

## QR scanner reliability

The QR camera preview now surfaces camera-provider/binding exceptions and provides a retry action. The scanner still returns raw decoded text to its caller; the caller remains responsible for validating payload type, version, size, identity and trust. Camera retry and scan behavior still require physical-device testing.

## Release gates

Before describing MeshLink as production-secure:

- Replace group-key-in-QR bearer invites with per-recipient authenticated encrypted envelopes.
- Specify and version the session key schedule; add test vectors and two-device interoperability tests.
- Add identity key-change detection and explicit fingerprint confirmation UX.
- Implement acknowledged group epoch rotation and stale-epoch rejection.
- Run two- and three-device acceptance tests across every claimed transport.
- Verify Android release signing, upgrade behavior, backup/restore and key-loss recovery.
- Perform an independent security review.

## Scope and limitations

This review is based on source inspection only. It is not a claim that every transport, crypto path, or UI flow has been exhaustively audited. See `DEVICE_TEST_CHECKLIST.md` and `TEST_PLAN.md` for runtime validation work.


## Recent mitigations (2026-11-17)

- Disabled public group QR export because the previous URI embedded the raw AES group key.
- Disabled legacy v1 group QR import; old invitations must not be treated as safe.
- New QR identity imports are untrusted by default.
- Re-importing an existing device ID with changed signing/agreement keys or fingerprint is rejected; the stored identity/trust state is preserved and a CRITICAL security event is recorded.
- Group member removal and group-key rotation UI messages now explicitly state that these are local-only operations and do not prove remote revocation or distribution.

These mitigations reduce unsafe behavior but do not complete the secure session protocol or group epoch distribution.


## Group epoch distribution implementation (2026-11-17)

- Added `GROUP_KEY_UPDATE` packet type.
- Added Room membership lookups and transactional admin-only rotation/removal operations.
- Group key updates are encrypted for each remaining trusted member, signed by the sender identity, and store-and-forward routed.
- Receivers verify the sender is trusted and an admin in the local group, validate the key length, reject stale epochs, apply optional member revocation, and acknowledge successful application.
- Group invitation receive validates creator/sender identity and key material. Existing-group invitations from non-admins are rejected.
- Invitation creation only records a member after the invite packet is successfully queued/sent.
- UI reports queued/sent counts and clarifies that delivery acknowledgements are asynchronous.

Open release gate: implement reliable all-member epoch acknowledgement/recovery and migrate pairwise encryption to authenticated ephemeral ECDH with transcript-bound HKDF and directional keys.
