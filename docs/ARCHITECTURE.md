# MeshLink Architecture Overview

MeshLink is built upon an offline-first, decentralized peer-to-peer architecture using Kotlin, Jetpack Compose, Material 3, and Room SQLite storage.

## Layers

1. **Presentation / UI Layer**: Jetpack Compose screens, ViewModels, and state flows observing local Room database queries via lifecycle-aware collectors.
2. **Domain / Business Logic Layer**: Routing engines, cryptography handlers, file transfer managers, voice recorders, and location sharers.
3. **Transport Layer**: Hybrid wireless drivers abstracting Bluetooth LE, Wi-Fi Direct, Wi-Fi Aware, and Local Wi-Fi LAN sockets into a unified event and packet stream.
4. **Data Layer**: Room database (`AppDatabase`), DAOs, and repository abstraction.
