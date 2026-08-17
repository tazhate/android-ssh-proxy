package com.example.sshproxy

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Simple SOCKS5 proxy server that forwards connections over an SSH session
 * using JSch's ChannelDirectTCPIP.
 */
class LocalSocks5Proxy(private val sshSession: Session) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 0

    fun start(): Int {
        serverSocket = ServerSocket(0)
        port = serverSocket!!.localPort
        isRunning = true
        LogManager.addLog("[SOCKS5] Proxy started on port $port")
        Thread { acceptLoop() }.start()
        return port
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val client = serverSocket!!.accept()
                Thread { handleClient(client) }.start()
            } catch (e: Exception) {
                if (isRunning) {
                    LogManager.addLog("[SOCKS5] Accept error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 handshake (no authentication)
            val version = input.read()
            if (version != 0x05) {
                client.close()
                return
            }
            val nmethods = input.read()
            repeat(nmethods) { input.read() }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Parse CONNECT request
            val cmd = input.read()
            if (cmd != 0x01) { // CONNECT only
                client.close()
                return
            }
            input.read() // RSV
            val addrType = input.read()
            val destHost = when (addrType) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4)
                    input.read(ip)
                    ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    String(domain)
                }
                0x04 -> { // IPv6 (skip for simplicity)
                    val ip = ByteArray(16)
                    input.read(ip)
                    // You can implement a proper IPv6 string conversion if needed
                    "::1"
                }
                else -> {
                    client.close()
                    return
                }
            }
            val destPort = (input.read() shl 8) or input.read()

            LogManager.addLog("[SOCKS5] Connecting to $destHost:$destPort")

            // Open SSH direct-tcpip channel
            val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.connect()

            // Send success response (BND.ADDR = 0.0.0.0:0)
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            output.flush()

            // Relay data bidirectionally
            val clientInput = client.getInputStream()
            val clientOutput = client.getOutputStream()
            val channelInput = channel.getInputStream()
            val channelOutput = channel.getOutputStream()

            relay(clientInput, channelOutput, "client->ssh")
            relay(channelInput, clientOutput, "ssh->client")

            // Wait for threads to finish
            clientInput.close()
            channel.disconnect()
            client.close()

        } catch (e: Exception) {
            if (!(e is SocketException && e.message?.contains("Socket closed") == true)) {
                LogManager.addLog("[SOCKS5] Error: ${e.message}")
            }
        }
    }

    private fun relay(input: InputStream, output: OutputStream, name: String) {
        Thread {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    output.write(buffer, 0, len)
                    output.flush()
                }
            } catch (_: Exception) {
                // closed
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        LogManager.addLog("[SOCKS5] Proxy stopped")
    }
}