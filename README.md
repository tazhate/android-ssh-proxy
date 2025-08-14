# Android SSH Proxy

Android application (Android 10+) for system-wide HTTP traffic proxying through an SSH local port-forwarding tunnel using `VpnService.setHttpProxy`.

## ✨ Features

*   **🚀 System-wide HTTP/HTTPS Proxy:** Tunnels all HTTP/HTTPS traffic through an SSH server.
*   **🔑 Secure Key Management:** Generates and stores SSH keys securely using the Android Keystore.
*   **🛡️ Host Key Verification:** Protects against MITM attacks by verifying server host keys.
*   **🔄 Automatic Reconnection:** Automatically reconnects to the server with an exponential backoff strategy.
*   **🧪 Connection Quality Monitoring:** Monitors connection latency and quality in real-time.
*   **🌐 "Test All Servers" Feature:** Tests the latency of all configured servers.
*   **✅ "Test Connection" without VPN:** Allows testing the internet connection without an active VPN.
*   **📱 Multi-language Support:** Available in English and Russian.
*   **🎨 Themes:** Supports system, light, and dark themes.

## 📱 How to Use

1.  **📲 Install the application** on an Android 10+ device.
2.  **🔑 On the first launch:**
    *   The application will automatically generate a new SSH key pair.
    *   Copy the public key from the setup wizard or the "Keys" section.
3.  **📋 Provide the public key** to your server administrator to add it to `~/.ssh/authorized_keys`.
4.  **🌐 Add your server:**
    *   Go to the "Servers" section and click the "+" button.
    *   Enter your server's details (name, host, port, and username).
5.  **🔌 Connect:**
    *   Select your server from the list and tap the "Connect" button.
    *   Grant VPN permission to the application.
    *   Wait for the status to change to "Connected".
6.  **✅ Done!** All your HTTP/HTTPS traffic is now routed through your SSH server.

## 🔧 Server Setup (for Administrators)

To set up your own proxy server, you can use the provided setup script in the "Instructions" section of the app. The script will automatically:

*   Create a restricted user for SSH tunneling.
*   Install and configure Tinyproxy as the HTTP proxy.
*   Set up the SSH key for authentication.
*   Restrict the user's access to only local port-forwarding.

**Server Structure:**

```bash
# Restricted user
/home/username/.ssh/authorized_keys  # Your public key

# HTTP proxy (Tinyproxy) listening on 127.0.0.1:8118
# SSH forwarding: phone:8080 -> server:8118
```

**Security Considerations:**

*   The user has minimal privileges (port-forwarding only).
*   The proxy is only accessible locally (127.0.0.1).
*   SSH keys are encrypted using the Android Keystore (AES-256-GCM).
*   Host keys are validated to prevent MITM attacks.
*   IPv6 is supported to prevent VPN bypass.

## 🔐 Security Features

### Private Key Encryption

*   **Android Keystore:** All private keys are encrypted and stored in the Android Keystore.
*   **AES-256-GCM:** Modern symmetric encryption is used.
*   **TEE/Secure Element:** Encryption keys are protected at the hardware level.
*   **Zero-knowledge:** Private keys cannot be extracted, even with root access.

### Host Key Validation (Known Hosts)

*   **Automatic Saving:** Host keys are automatically saved on the first connection.
*   **SHA-256 Fingerprints:** Servers are identified by their SHA-256 fingerprints.
*   **MITM Protection:** The connection is blocked if the host key changes.
*   **User Confirmation:** A dialog is shown to the user to accept host key changes.

## ⚠️ Limitations

*   **HTTP Proxy Only:** The application does not modify HTTPS/TLS traffic directly and relies on system support for proxying.
*   **Android 10+ Required:** The `setHttpProxy` API is only available on Android 10 and above.
*   **No Per-app Exclusions:** The VPN is configured without routes, and the HTTP proxy is applied globally.
*   **Server Script:** The server setup script requires root/sudo privileges and should be reviewed before execution.

## 🛠️ Building from Source

```bash
./gradlew assembleDebug
# or
./gradlew assembleRelease
```

## 🐛 Debugging

```bash
# Main components
adb logcat -s SshProxyService AppLog ConnectionHealthMonitor

# Security
adb logcat -s SecureHostKeyVerifier KnownHostsManager KeystoreManager

# Ping and IP monitoring
adb logcat -s PingMonitor IpLocationService

# All application components
adb logcat | grep -E "(SshProxy|SSH|VPN|Ping|android-ssh-proxy)"
```

## 📄 Disclaimer

This software is provided "as is", without warranty of any kind. Use at your own risk. The use of this application may violate the policies of your network or service provider. This application implements cryptographic functions; ensure compliance with local laws.

## 📜 License

Apache-2.0
