package com.example.sshproxy.tun2socks

import com.example.sshproxy.LogManager

object HevSocks5Tunnel {
    // Native method signature matches the .so file: (tun_fd, "127.0.0.1:port", mtu)
    external fun start(tunFd: Int, socksAddr: String, mtu: Int): Int
    external fun stop(): Int

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            LogManager.addLog("[hev-socks5-tunnel] Library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            LogManager.addLog("[ERROR] Failed to load hev-socks5-tunnel: ${e.message}")
        }
    }
}
