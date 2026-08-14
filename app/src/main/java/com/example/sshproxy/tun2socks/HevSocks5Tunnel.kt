package com.example.sshproxy.tun2socks

import com.example.sshproxy.LogManager

/**
 * JNI wrapper for hev-socks5-tunnel native library.
 * Loads libhev-socks5-tunnel.so and provides start/stop methods.
 */
object HevSocks5Tunnel {
    // Native methods
    external fun start(tunFd: Int, socksHost: String, socksPort: Int, mtu: Int): Int
    external fun stop(): Int

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            LogManager.addLog("[hev-socks5-tunnel] Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            LogManager.addLog("[ERROR] Failed to load hev-socks5-tunnel: ${e.message}")
        }
    }
}