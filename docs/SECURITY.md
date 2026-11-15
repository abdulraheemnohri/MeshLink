# MeshLink Security & Privacy Specification

MeshLink enforces a zero-trust security and privacy model:

- **Key Management**: Android KeyStore-backed elliptic curve (EC P-256) signing and agreement key pairs generated on device.
- **End-to-End Encryption**: ECDH shared secret derivation combined with HKDF-SHA256 and AES-256-GCM authenticated encryption for private messages and group payloads.
- **Relay Privacy**: Intermediate relay nodes forward encrypted byte payloads without access to plaintext content or private keys.
- **Peer Verification**: QR code and NFC identity payloads establish verified trust fingerprints.
- **Biometric Vault Lock**: Optional hardware biometric or device screen credential confirmation required on app launch.
