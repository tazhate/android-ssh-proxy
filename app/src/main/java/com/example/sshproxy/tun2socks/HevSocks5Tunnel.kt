package com.example.sshproxy.tun2socks

import com.example.sshproxy.LogManager

object HevSocks5Tunnel {
    // Native methods – match the ones from TProxyService
    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            LogManager.addLog("[hev-socks5-tunnel] Library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            LogManager.addLog("[ERROR] Failed to load hev-socks5-tunnel: ${e.message}")
        }
    }
}
