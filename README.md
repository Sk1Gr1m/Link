# Link

Link is a decentralized, peer-to-peer (P2P) communication platform for Android that eliminates the need for central servers. By combining high-performance WebRTC data channels with a robust Python-based Distributed Hash Table (DHT), Link provides a secure, private, and resilient way to communicate.

## 🚀 Key Features

- **Decentralized Discovery**: Uses a Kademlia-based DHT for peer discovery without a central directory or server.
- **Secure Messaging**: High-speed peer-to-peer text and image sharing powered by WebRTC DataChannels.
- **Identity Privacy**: Identity is managed via cryptographic fingerprints and public/private key pairs generated and stored locally.
- **Seamless Pairing**: Connect with peers via QR code scanning or background DHT lookups.
- **Offline Resilience**: Local message and peer persistence using Room Database.
- **Hybrid Architecture**: Leverages Kotlin for UI/Android integration and Python (via Chaquopy) for advanced networking and DHT logic.

## 🏗 Architecture

Link follows a unique hybrid architecture to balance Android performance with networking flexibility:

- **Frontend**: Built with **Jetpack Compose** for a modern, reactive UI.
- **P2P Layer**: **WebRTC** provides the encrypted pipe for data transfer, ensuring low latency and high security.
- **Signaling & Discovery**: A custom **Python-based DHT node** (using the `kademlia` library) handles peer addresses and signaling fallback.
- **Identity Management**: Cryptographic keys and fingerprints are managed through a dedicated `LinkIdentityManager`.
- **Persistence**: **Room SQL** ensures that chats and trusted peer identities are stored securely on-device.

## 🛠 Tech Stack

- **Kotlin**: Core Android application logic and UI.
- **Python**: DHT implementation and cryptographic utilities (via Chaquopy).
- **WebRTC**: Peer-to-peer data transport.
- **Jetpack Compose**: Modern UI toolkit.
- **Room**: Local database for persistence.
- **Kademlia**: Distributed Hash Table protocol.

## 📦 Getting Started

### Prerequisites

- Android Studio Koala or newer.
- Android SDK 34+.
- Python 3.10+ (automatically managed by Chaquopy during build).

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/sk1grim/link.git
   ```
2. Open the project in **Android Studio**.
3. Sync the project with Gradle files.
4. Run the `:app` module on a physical Android device (recommended for network features).

## 🔒 Privacy & Security

Link is designed with privacy as its core principle:
- **No Servers**: Your data never touches a central server. All communication is direct between peers.
- **Local Keys**: Private keys are generated and stored exclusively on your device.
- **Fingerprinting**: Peer identity is verified using cryptographic fingerprints to prevent man-in-the-middle attacks.
- **End-to-End Encryption**: All data channels are encrypted using WebRTC's native DTLS/SRTP protocols.

## 📂 Project Structure

- `app/src/main/java/linkfront`: Kotlin source code (UI, Services, WebRTC Management).
- `app/src/main/python/linkfront`: Python networking logic (DHT node, packet handling, crypto).
- `app/src/main/res`: Android resources, themes, and layouts.

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0** - see the [LICENSE](LICENSE) file for details.

---

*Note: This project is currently in active development. Use with caution for sensitive communication.*
