package com.example.sshproxy.tun2socks

import com.example.sshproxy.LogManager

object HevSocks5Tunnel {
    // Native signature: int hev_socks5_tunnel_run(int tun_fd, const char* socks_addr, int mtu)
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
