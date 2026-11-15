# MeshLink Routing Engine

MeshLink implements hybrid Delay-Tolerant Networking (DTN) routing:

1. **Direct Routing**: Transmits directly when the destination peer is within direct wireless range.
2. **Store-and-Forward**: Queues undelivered packets in local Room database storage until destination or relay reconnects.
3. **Epidemic Routing**: Floods packets to suitable connected peers with duplicate suppression and hop limits.
4. **Spray-and-Wait**: Distributes a limited number of copies ($L$) across relay nodes before waiting for direct delivery.
