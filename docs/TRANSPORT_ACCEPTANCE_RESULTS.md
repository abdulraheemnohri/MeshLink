# MeshLink physical transport acceptance results

Status as of 2026-11-17: **NOT RUN — no physical multi-device test evidence is attached to this repository review.**

Do not change a result to PASS based on compilation, unit tests, adapter registration or a single-device emulator.

## Test environment record

Complete this section for each test session.

| Field | Value |
|---|---|
| Test date/time | NOT RECORDED |
| App version / commit SHA | NOT RECORDED |
| Device A model / Android | NOT RECORDED |
| Device B model / Android | NOT RECORDED |
| Device C model / Android (relay tests) | NOT RECORDED |
| Google Play services versions | NOT RECORDED |
| Network topology / internet disabled? | NOT RECORDED |
| Permissions granted | NOT RECORDED |
| Tester / diagnostic bundle | NOT RECORDED |

## Transport result tracker

| Transport | Discovery | Identity/authentication | Bidirectional packets | Reconnect | Large payload/file | Result |
|---|---|---|---|---|---|---|
| Local LAN / Wi-Fi | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Mobile hotspot via LAN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Bluetooth LE | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Bluetooth Classic RFCOMM | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Wi-Fi Direct | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Wi-Fi Aware | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Google Nearby Connections | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| NFC NDEF bootstrap | NOT RUN | NOT RUN | N/A | N/A | N/A | NOT RUN |
| QR image/camera bootstrap | NOT RUN | NOT RUN | N/A | N/A | N/A | NOT RUN |

## Required evidence per transport

- Record both device identities and compare verified fingerprints.
- Export the app diagnostics/network event log from both devices.
- Capture discovery/connect/disconnect timestamps and transport selection settings.
- Send at least 100 short messages in both directions and record missing/duplicate counts.
- Exercise radio-off/on, app background/foreground and process restart.
- For data transports, transfer a multi-chunk file and compare sender/receiver SHA-256.
- For Nearby, record Play services version and confirm the app falls back only when policy permits.
- For Wi-Fi Direct/Aware, record whether both device chipsets expose the required feature.
- For NFC/QR, record the actual tag/handoff or image source used.

## Three-device relay evidence

| Scenario | Result |
|---|---|
| A discovers B and B discovers C | NOT RUN |
| A sends to C while A cannot directly reach C | NOT RUN |
| B forwards packet without decrypting application payload | NOT RUN |
| Duplicate packet is suppressed | NOT RUN |
| TTL/hop limit stops forwarding | NOT RUN |
| C ACK returns through B to A | NOT RUN |
| B disconnects and route recovers | NOT RUN |

A PASS requires the exact build SHA, device records, test output and diagnostics to be attached to the release evidence. Keep failed and unsupported results; do not silently omit them.
