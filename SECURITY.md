# Security Policy and Data Usage

This document establishes the security framework for the Link project and details the methodologies employed to ensure the privacy and integrity of user communications.

## Security Architecture

Link is built upon a decentralized, peer-to-peer (P2P) architecture. The project operates under a strict "Local Only" philosophy, ensuring that user data remains exclusively on the host device. By removing central servers from the communication path, Link eliminates the primary point of failure and mass surveillance common in traditional messaging platforms.

### Communication Encryption
Every communication transmitted through the Link protocol is secured using end-to-end encryption (E2EE). Text and media are encrypted at the source using industry-standard cryptographic primitives. Decryption is only possible by the intended recipient, ensuring that even if traffic is intercepted at the network level, the content remains inaccessible to unauthorized parties.

### Identity and Authentication
Authentication is facilitated through a unique cryptographic Identity Key and a derived Fingerprint. These identifiers are fundamental to the security model as they allow peers to verify the identity of their contacts and protect against "Man-in-the-Middle" (MITM) attacks. Usernames are self-assigned and stored locally; they are exchanged during the initial handshake solely to provide a human-readable interface for the cryptographic fingerprints.

### Network Infrastructure and Connectivity
As a serverless system, Link requires specific network metadata to facilitate direct device-to-device connections. The system utilizes a Distributed Hash Table (DHT) for peer discovery and WebRTC for establishing encrypted data tunnels. This process necessitates the temporary use of local and public IP addresses and port numbers. NAT traversal is achieved via STUN/TURN servers which act as network relays to bypass firewalls; however, these servers function only as routing nodes and possess no technical capability to decrypt or inspect the contents of the encrypted traffic passing through them.

### Data Persistence
Persistent data, including chat history and peer public keys, is stored in a localized, encrypted database on the mobile device. This data is never synchronized with cloud services or external repositories. The local storage model ensures that users maintain absolute sovereignty over their personal communication history.

## Supported Versions

The table below specifies the versions of Link currently receiving security updates.

| Version | Status             |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

The Link project maintains an open approach to security disclosures. If a security vulnerability is identified, contributors are encouraged to report the finding by opening a new issue on the GitHub repository.

While public disclosure is often a sensitive topic, the project utilizes GitHub issues as the primary mechanism for tracking and remediating bugs. Reports should include a comprehensive description of the vulnerability and, where possible, a set of steps to reproduce the issue to facilitate a rapid technical response.
