# MeshLink Security Review

Last reviewed: 2026-11-17

This is a source-level review, not a penetration test or a formal cryptographic audit. A successful Android CI build does not establish that the network protocol is secure in deployment.

## Current high-priority findings

### 1. Group invitation QR exposes the group encryption key

**Status: OPEN — do not distribute group invitation QR codes publicly.**

`MeshCryptoEngine.encodeGroupInviteUri()` currently places the raw group AES key in the `key` query parameter. Anyone who obtains a screenshot, copied URI, QR image, chat export, or log containing that URI can recover the group key and decrypt group traffic encrypted with that key.

The current QR invite format is therefore a bearer secret, not a safe public invitation. Do not post it publicly or send it through untrusted channels. A safe replacement requires an authenticated, per-recipient encrypted key envelope delivered over an authenticated peer session, plus an invite identifier/expiry and explicit recipient acceptance. Simply deleting the key field would break the existing QR join flow, so that protocol migration must be implemented end-to-end before changing the wire format.

### 2. Pairwise key agreement uses long-lived identity ECDH keys

**Status: OPEN — forward secrecy not provided.**

The current pairwise key derivation uses the long-lived local agreement private key and the peer's long-lived public agreement key. The HMAC construction is a legacy one-step derivation rather than a complete HKDF extract/expand schedule with transcript binding and directional keys. Compromise of a long-lived agreement private key can expose recorded sessions that used that key.

A future protocol version should use authenticated ephemeral ECDH, transcript-bound HKDF-SHA-256, distinct send/receive keys, explicit protocol/version context, and replay-resistant session identifiers. Do not silently change this derivation without version negotiation and compatibility tests.

### 3. QR identity import is not out-of-band identity verification

**Status: OPEN — user fingerprint comparison is required.**

Importing a public identity payload establishes a local peer record and QR trust flag, but a QR payload alone does not prove that the person holding the device is the intended contact. Users should compare the displayed fingerprint through a separate trusted channel before relying on identity authenticity. A changed key for an existing device ID must be treated as a security event, not silently accepted.

### 4. Group key rotation and member removal need protocol enforcement

**Status: OPEN — local state update is not proof of remote revocation.**

Changing the locally stored group key or removing a member does not, by itself, prove that all remaining members received the new key or that the removed member cannot access future traffic. Rotation needs authenticated epoch distribution, acknowledgements, and rejection of stale-epoch messages. A removed member may retain old keys and decrypt historical content already captured.

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
