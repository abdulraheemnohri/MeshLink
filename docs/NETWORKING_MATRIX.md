# MeshLink networking and transport acceptance matrix

Last source audit: 2026-11-17

This document distinguishes source implementation, automated tests and physical-device acceptance. A class or adapter in the repository is not proof that two Android devices can interoperate on a given OEM build.

## Transport inventory

| Transport | Source implementation | What it provides | Platform requirements | Physical acceptance |
|---|---|---|---|---|
| Local LAN / Wi-Fi / WLAN | Implemented | Private IPv4 discovery and TCP packet delivery over a reachable local interface | Same reachable LAN/subnet; Android network and multicast behavior | NOT RUN |
| Mobile hotspot | Uses Local LAN adapter | IP path when one phone joins the other's hotspot | Hotspot client isolation/subnet behavior must permit peer traffic | NOT RUN |
| Ethernet / USB tethering | Uses Local LAN interface allow-list | IP path when Android exposes a private interface | Compatible adapter/tethering and reachable private IPv4 interface | NOT RUN |
| Bluetooth LE | Implemented | Discovery and GATT packet transport with bounded framing | Bluetooth LE hardware; Android 12+ Nearby Devices permissions | NOT RUN |
| Bluetooth Classic RFCOMM | Implemented | Paired-device socket transport with signed identity HELLO and nonce challenge-response | Bluetooth enabled, OS-level pairing, Nearby Devices permissions | NOT RUN |
| Wi-Fi Direct | Implemented | Android P2P discovery and peer data path | Wi-Fi Direct hardware and Android/OEM P2P support | NOT RUN |
| Wi-Fi Aware | Implemented | NAN discovery and data path | Android 8+ plus supported Wi-Fi Aware hardware/firmware | NOT RUN |
| Google Nearby Connections | Implemented as optional adapter | P2P_CLUSTER advertising/discovery and framed byte payload exchange (effective MeshLink routing cap is 32 KiB per packet) | Compatible Google Play services and Nearby permissions on both devices | NOT RUN |
| NFC | Bootstrap only | NDEF URI identity handoff | NFC hardware and a supported NDEF tag/handoff path | NOT RUN |
| QR | Bootstrap only | Identity QR display, PNG save/share, image import and camera scan | Camera permission for camera scanning; document picker for image import | NOT RUN |
| Internet / cellular / WAN | No general gateway implementation | No guaranteed cross-network relay | Would require explicit relay infrastructure, NAT traversal and additional threat model | NOT APPLICABLE |
| Satellite / LoRa / Zigbee / Thread | Not implemented | External radio/gateway concepts only | External hardware and gateway software | NOT APPLICABLE |

## Transport selection policy

The packet protocol is independent of the radio carrying it.

- **Automatic:** rank running, discovered, enabled, payload-compatible endpoints using configured preference, recent path failures and signal strength.
- **Prefer:** try preferred transports first; use another discovered path only when fallback is enabled.
- **Only:** strict mode; do not silently use another radio.
- **Disabled:** do not scan, advertise or send over that adapter.
- **Per-peer paths:** preserve each adapter's own endpoint locator. A BLE address must never be passed to LAN, nor a Wi-Fi Direct locator to another adapter.

Current source implements persisted Automatic / Prefer / Only policy, per-transport enable/disable, path-health tracking and sequential fallback across separately discovered paths. This is not simultaneous multipath bonding.

## Physical acceptance protocol

Run each transport independently before enabling multiple transports. Use Device A and Device B; use Device C for relay tests.

1. Install the same build and record its commit SHA on both phones.
2. Record device model, Android version, security patch, chipset if known, Google Play services version, app version and permissions.
3. Import identities and verify fingerprints out of band. Do not treat MAC addresses, IP addresses or Nearby endpoint IDs as identities.
4. Disable internet access for local/offline tests.
5. Enable only the transport under test.
6. Confirm discovery, signed identity binding, bidirectional packet delivery and reconnect after radio toggle.
7. Send short text, a large packet within adapter limits and a multi-chunk file where supported.
8. Verify message ACKs, file SHA-256, replay rejection and that a failed adapter does not corrupt or reroute through an unselected adapter.
9. Repeat with screen off, battery saver and background restrictions.
10. Export diagnostics and record the result in `TRANSPORT_ACCEPTANCE_RESULTS.md`.

## Per-transport pass criteria

- **LAN:** A and B discover each other without internet; signed packet delivery works in both directions; router/client isolation is reported as unavailable rather than false success.
- **BLE:** both directions work over GATT; fragmented frames reassemble correctly; radio interruption recovers; BLE failure does not disable LAN.
- **Bluetooth Classic:** RFCOMM pairing is performed by Android; signed HELLO and nonce challenge-response complete before application packets are accepted; socket reconnect binds to the same cryptographic identity.
- **Wi-Fi Direct:** P2P discovery, group-owner/client setup, endpoint address resolution, bidirectional packet delivery and teardown/reconnect work on the tested OEMs.
- **Wi-Fi Aware:** publish/subscribe discovery and data path work on supported devices; unsupported hardware is explicitly reported.
- **Nearby:** both devices have compatible Google Play services; endpoint discovery, authentication, byte payload exchange, disconnect/reconnect and fallback all work. Nearby must remain optional on devices without Play services.
- **NFC:** only claim NDEF identity bootstrap after a real supported tag/handoff exchange; NFC is not a general packet/file transport.
- **QR:** saved/shared PNG and Gallery/Files import decode correctly; malformed and unrelated QR payloads are rejected; camera scanner works on-device.

## Safety and release rules

1. LAN membership, Bluetooth pairing and Nearby endpoint IDs are not MeshLink identity verification.
2. Require signed MeshLink identity binding before trusted messaging.
3. Apply packet signature verification, replay protection, expiry and payload limits on every adapter.
4. Respect Android runtime permissions, radio toggles and foreground-service limits.
5. Report hardware capability separately from actual test status.
6. Do not claim guaranteed emergency/public-safety delivery.
7. Do not call a transport production-verified until its physical acceptance row has evidence attached.
