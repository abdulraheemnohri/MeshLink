# MeshLink security notes

MeshLink is an experimental offline communication project. Do not rely on it as a production emergency-response system or as a substitute for established secure messaging.

## Current security boundaries

- Peer handshakes prove possession of the signing key presented in the handshake, but an unauthenticated first contact is still vulnerable to a man-in-the-middle attack.
- Verify a peer's QR identity fingerprint out of band before treating that peer as a trusted contact.
- The current agreement key is static ECDH. It does not provide forward secrecy or post-compromise security.
- Application identity key pairs are generated in software and wrapped at rest using an Android Keystore AES key. This is not equivalent to generating non-exportable signing and agreement keys inside secure hardware.
- Packet signatures authenticate the immutable packet fields currently covered by the signing string. Hop/routing metadata is mutable and requires a separately reviewed per-hop authentication design before production use.
- Transport write success is not proof of end-to-end delivery. Message delivery acknowledgements and file-transfer acknowledgements are separate protocol events.
- Local discovery beacons are untrusted hints. They must never be treated as authenticated identities.
- Store-and-forward and emergency broadcasts are best-effort. They depend on compatible nearby devices, relay availability, battery, Android background policy and radio conditions.

## Safe-use guidance

1. Exchange and verify identity QR codes in person or through another trusted channel.
2. Do not use unverified peers for sensitive communications.
3. Keep Android and device firmware updated.
4. Test on the exact devices and Android versions that will be used.
5. Do not assume BLE, Wi-Fi Direct or Wi-Fi Aware is available on every Android device.
6. Do not rely on MeshLink for emergency dispatch, medical care, public-safety coordination or guaranteed delivery.

## Reporting a vulnerability

Please report reproducible security issues through the repository's GitHub Security Advisories feature where it is enabled. Include affected commit, Android version, device model, reproduction steps and sanitized logs. Never include private keys, QR payloads, message plaintext or personal location data in a report.

## Release gate

A production release requires independent cryptographic review, protocol fuzzing, multi-device testing, persistence/recovery tests and an explicit threat model. CI compilation and unit tests alone are not security certification.
