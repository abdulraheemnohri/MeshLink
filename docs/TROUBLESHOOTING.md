# MeshLink Troubleshooting

## Common Issues & Solutions

- **Peers not discovering each other**: Ensure both devices have Bluetooth, Wi-Fi, and Location services enabled. On Android 13+, ensure the `NEARBY_WIFI_DEVICES` permission is granted.
- **Messages stuck in DTN queue**: Offline messages remain queued in Store-and-Forward until a direct link or multi-hop relay route becomes active.
- **Biometric lock prompt failure**: If hardware biometrics are not enrolled, MeshLink gracefully falls back to device screen PIN/password confirmation.
