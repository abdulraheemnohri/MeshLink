# MeshLink networking and transport selection

This document separates transports that are implemented in the current Android source from platform capabilities that require a dedicated adapter. A transport name in the protocol enum is not proof that an adapter is implemented.

## Network modes

| Mode | What it means | Current status / constraints |
|---|---|---|
| Same Wi-Fi network | Phones connected to the same router / access point | Supported through Local LAN UDP discovery + TCP packet delivery, subject to Android/OEM network isolation |
| WLAN / Wi-Fi | General wireless LAN connectivity | Uses the active WLAN interface through Local LAN; Wi-Fi itself is a link, not a separate application protocol |
| Local Area Network (LAN) | Private IPv4 network, including compatible home/office networks | Local LAN adapter filters private IPv4 and supported mesh interfaces |
| Mobile hotspot | One phone exposes a hotspot and another joins | Intended to use Local LAN; hotspot subnet/interface behavior varies by OEM and requires device testing |
| Wi-Fi Direct (P2P) | Direct group-owner/client Wi-Fi connection without a router | Dedicated adapter exists; client IP discovery is best-effort and OEM-dependent |
| Wi-Fi Aware (NAN) | Nearby discovery/data path without joining a conventional WLAN | Dedicated adapter exists on Android 8+ devices that expose Wi-Fi Aware; hardware/carrier/OEM support varies |
| Bluetooth LE | Low-power discovery and GATT data | Dedicated adapter exists; throughput is limited and transfers are fragmented |
| NFC | Tap to exchange a small bootstrap payload / peer identity URI | Manifest permission and intent filter exist; this is not a general message/file transport and an end-to-end NDEF exchange adapter is not yet verified |
| QR code | Optical, one-way-at-a-time exchange of identity/bootstrap data | Identity URI encoding/import exists; QR is not a continuous network transport |
| Bluetooth Classic RFCOMM | Paired-device serial data channel | Enum capability only; no verified RFCOMM transport adapter in current transport registration |
| Android Nearby Connections | Google Play services nearby peer-to-peer API | Not currently integrated. It can use Bluetooth, BLE and Wi-Fi under the hood; requires Play services and is not an independent radio |
| USB / Ethernet / USB tethering | Local IP network over a cable or adapter | Can work through Local LAN if Android exposes a supported private interface; no dedicated USB transport |
| Internet / WAN | Public IP connectivity across networks | Not a default offline mesh path. Requires explicit relay infrastructure, addressing, NAT traversal and security design |
| Cellular data / 4G / 5G | Carrier IP connectivity | Not direct phone-to-phone radio messaging. Can carry an explicitly configured internet relay; carrier restrictions apply |
| Satellite | Satellite modem / carrier service | No direct satellite radio adapter; only a future IP gateway integration with compatible hardware/service |
| Thread / Zigbee / LoRa / Wi-Fi HaLow | Specialized low-power radio networks | Not built into ordinary Android phones; requires external radio hardware and a gateway/adapter |

## Protocol selection

The packet protocol (MLP/1) is transport-independent. The transport is the actual link carrying the packet. Do not confuse packet routing strategies with radio/link selection.

### Desired user controls

- **Automatic (recommended):** choose a reachable, supported transport based on peer reachability, capability, packet size, link quality, and recent send failures; retry through other supported links when appropriate.
- **Prefer one transport:** BLE, Wi-Fi Direct, Wi-Fi Aware, same-network LAN, or hotspot/LAN. The preference must fall back only when the user permits fallback.
- **Only this transport:** strict mode. Do not silently send over another radio; queue the packet if the selected link is unavailable.
- **Disable transport:** never scan, advertise, or send using that adapter.
- **Per-peer connection view:** show all currently discovered paths to the same cryptographic peer identity. Do not treat a BLE MAC, IP address, Wi-Fi Direct address, or Nearby endpoint ID as the peer's identity.

Current implementation note: MeshRoutingEngine already prefers the endpoint's discovered transport and attempts other registered transports as fallback. A complete persisted user-facing transport policy (Automatic / Prefer / Only / Disabled) and multi-path per-peer UI still need implementation; do not present them as already shipped.

## Safety and compatibility rules

1. Use the cryptographic MeshLink device identity and signed handshake for trust. Network addresses are temporary locators only.
2. Do not accept packets solely because a device is on the same WLAN/LAN. Private network membership is not authentication.
3. Apply packet signature verification, replay protection, expiry, size limits and rate limits on every transport.
4. Never expose the local TCP listener to arbitrary public interfaces. LAN discovery and sockets must be scoped to intended private interfaces.
5. Respect Android runtime permissions, foreground-service limits, radio toggles and OEM background restrictions.
6. If a transport is unsupported, disabled, permission-denied, or unavailable, show its reason and continue only according to the selected fallback policy.
7. NFC and QR are bootstrap / contact-exchange methods, not substitutes for continuous network links.
8. Android Nearby Connections must be treated as an optional Google Play services integration, not as a guaranteed offline capability on every Android distribution.
9. Internet, cellular and satellite gateways must be explicit opt-in bridge paths; never imply end-to-end offline delivery when a gateway is involved.
10. No protocol is guaranteed to work on every Android phone. Report detected capabilities separately from tested interoperability.

## Validation matrix

Before marking a path release-ready, test two physical phones in both directions, then a three-phone relay scenario where applicable. Record Android versions, OEM, chipset, permission state, network topology, packet sizes, transfer hashes, reconnect behavior and battery impact in [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md).

MeshLink is best-effort communication software. It must not be represented as guaranteed emergency, medical, or public-safety infrastructure.


## Transport policy implementation status (November 2026)

A persistent SharedPreferences-backed `TransportPolicyManager` is now part of the app container. It supports:

- Automatic mode: rank currently running, discovered and payload-compatible adapters by configured preference, recent failures and RSSI.
- Prefer mode: use the configured preference order; fallback is controlled by the policy.
- Only mode: strict selection; never returns a fallback transport.
- Disabled adapters: excluded from candidate selection.
- Same-device destination: returns a local-device delivery plan without requiring a radio.
- Oversized payloads, stopped adapters, undiscovered endpoints and repeated failures are excluded.

The manager is a policy component. The routing engine still needs to consume its plan at the packet-dispatch call site and the settings UI must expose/save the policy before these controls are considered end-to-end shipped. The manager has unit coverage for policy decisions.

### Network types versus actual adapters

The following are separate concepts, not all independent radio protocols:

- Same device: local in-process delivery; no network or radio.
- Same Wi-Fi / WLAN / LAN: IP connectivity carried by the active Wi-Fi interface and Local LAN adapter.
- Hotspot: an IP subnet exposed by a phone; uses Local LAN when interface discovery and routing permit.
- USB tethering / Ethernet: IP connectivity; can use Local LAN if Android exposes the interface.
- Wi-Fi Direct and Wi-Fi Aware: dedicated Android peer-to-peer paths.
- BLE: dedicated GATT path.
- NFC and QR: short-range / optical bootstrap, not continuous mesh data transports.
- Bluetooth Classic RFCOMM and Google Nearby Connections: not registered data adapters yet.
- Cellular / Internet / satellite: gateway paths, not direct offline phone-to-phone links.
- LoRa / Zigbee / Thread / Wi-Fi HaLow: external-radio gateway integrations, not phone-native transports.


## End-to-end source integration update

- Transport selection policy is persisted with Android SharedPreferences and exposed through the app container and ViewModel.
- Settings now exposes Automatic, Prefer and Only modes, fallback control, and per-transport enable/disable preferences.
- Packet dispatch enforces disabled transports and strict Only mode. It deliberately does not pass a BLE address, LAN IP or Wi-Fi Direct locator to a different adapter as a fake fallback; a fallback requires a separately discovered endpoint for that peer and transport.
- Packets addressed to the local device are handled as local loopback delivery and marked delivered without opening a radio.
- Policy tests cover local-device selection, preference ranking, strict mode, payload-size rejection, disabled transports, persistence and fallback disabling.

NFC status remains identity import through Android NDEF-discovered URI intents. Android Beam phone-to-phone NDEF push is not available on modern Android releases; therefore MeshLink does not claim that two phones can exchange arbitrary data simply by tapping. NFC remains a bootstrap path and QR remains the universal fallback.
