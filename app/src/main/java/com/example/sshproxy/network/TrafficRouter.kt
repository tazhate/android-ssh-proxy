package com.example.sshproxy.network

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.sshproxy.LogManager

class TrafficRouter(
    private val vpnService: android.net.VpnService,
    private val tunFileDescriptor: FileDescriptor,
    private val tunnelSocket: Socket,
    private val dnsServer: String = "1.1.1.1"   // user‑configurable DNS
) {
    private val TAG = "TrafficRouter"
    private var isRunning = false
    private val KEEP_ALIVE_INTERVAL = 3000L

    // UDP socket for DNS forwarding
    private val dnsSocket = DatagramSocket()
    private val dnsPort = 53

    fun start() {
        try {
            Log.d(TAG, "🔥 TrafficRouter.start() CALLED!")
            LogManager.addLog("TrafficRouter start() called")

            isRunning = true
            Thread {
                try {
                    LogManager.addLog("Router thread started")
                    val inputStream = FileInputStream(tunFileDescriptor)
                    val outputStream = FileOutputStream(tunFileDescriptor)
                    val tunnelInput = tunnelSocket.getInputStream()
                    val tunnelOutput = tunnelSocket.getOutputStream()
                    val buffer = ByteArray(32767)

                    // Keep-alive thread (sends space every 3s)
                    val keepAliveThread = Thread {
                        while (isRunning) {
                            try {
                                Thread.sleep(KEEP_ALIVE_INTERVAL)
                                if (isRunning && tunnelSocket.isConnected && !tunnelSocket.isClosed) {
                                    tunnelOutput.write(32)
                                    tunnelOutput.flush()
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    keepAliveThread.start()

                    // ---------- READ THREAD (TUN → SSH) with DNS detection ----------
                    val readThread = Thread {
                        LogManager.addLog("Read thread started")
                        while (isRunning) {
                            try {
                                val len = inputStream.read(buffer)
                                if (len > 0) {
                                    // Check if this is a UDP DNS packet (port 53)
                                    if (isDnsQuery(buffer, len)) {
                                        handleDnsQuery(buffer, len, outputStream)
                                    } else {
                                        // Normal TCP traffic → forward to SSH
                                        tunnelOutput.write(buffer, 0, len)
                                        tunnelOutput.flush()
                                    }
                                }
                            } catch (e: Exception) {
                                if (isRunning) LogManager.addLog("Read thread error: ${e.message}")
                                break
                            }
                        }
                        LogManager.addLog("Read thread stopped")
                    }

                    // ---------- WRITE THREAD (SSH → TUN) ----------
                    val writeThread = Thread {
                        LogManager.addLog("Write thread started")
                        while (isRunning) {
                            try {
                                val len = tunnelInput.read(buffer)
                                if (len > 0) {
                                    outputStream.write(buffer, 0, len)
                                    outputStream.flush()
                                }
                            } catch (e: Exception) {
                                if (isRunning) LogManager.addLog("Write thread error: ${e.message}")
                                break
                            }
                        }
                        LogManager.addLog("Write thread stopped")
                    }

                    readThread.start()
                    writeThread.start()
                    readThread.join()
                    writeThread.join()
                    keepAliveThread.join()

                } catch (e: Exception) {
                    LogManager.addLog("Router thread error: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            LogManager.addLog("FATAL ERROR in TrafficRouter.start(): ${e.message}")
        }
    }

    // ---------- DNS HELPER FUNCTIONS ----------

    // Checks if the packet is a UDP packet destined to port 53 (DNS)
    private fun isDnsQuery(data: ByteArray, len: Int): Boolean {
        if (len < 28) return false // IPv4 header (20) + UDP header (8) minimum
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return false
        val dstPort = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
        return dstPort == 53
    }

    // Handles a DNS query: forward to upstream DNS via UDP and write response back to TUN
    private fun handleDnsQuery(data: ByteArray, len: Int, tunOutput: FileOutputStream) {
        try {
            // Extract source IP and port from IP header
            val srcIp = data.copyOfRange(12, 16) // 4 bytes
            val srcPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            // DNS payload starts after IP header (20) + UDP header (8) = 28 bytes
            val dnsData = data.copyOfRange(28, len)

            // Forward to upstream DNS server via UDP
            val dnsServerAddr = InetAddress.getByName(dnsServer)
            val packet = DatagramPacket(dnsData, dnsData.size, dnsServerAddr, dnsPort)
            dnsSocket.send(packet)

            // Wait for response (timeout 5 seconds)
            dnsSocket.soTimeout = 5000
            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            dnsSocket.receive(responsePacket)

            // Build a UDP packet to send back to the TUN interface
            val responseData = responsePacket.data.copyOf(responsePacket.length)

            // Build the complete IP + UDP + DNS response packet
            val outPacket = buildUdpResponsePacket(
                srcIp, srcPort, responseData
            )

            tunOutput.write(outPacket)
            tunOutput.flush()
            LogManager.addLog("[DNS] Forwarded query, response size: ${responseData.size} bytes")
        } catch (e: Exception) {
            LogManager.addLog("[DNS] Error: ${e.message}")
        }
    }

    // Build a UDP response packet: IP header + UDP header + DNS data
    private fun buildUdpResponsePacket(
        destIp: ByteArray,  // original source IP (now destination for the response)
        destPort: Int,      // original source port
        dnsResponse: ByteArray
    ): ByteArray {
        // Total length: IP header (20) + UDP header (8) + DNS data length
        val totalLen = 20 + 8 + dnsResponse.size
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // ----- IP HEADER (20 bytes) -----
        buffer.put(0x45.toByte()) // Version 4, header length 5 (20 bytes)
        buffer.put(0.toByte())    // DSCP + ECN
        buffer.putShort(totalLen.toShort()) // Total length
        buffer.putShort(System.currentTimeMillis().toShort()) // Identification
        buffer.putShort(0)        // Flags + Fragment offset
        buffer.put(64.toByte())   // TTL
        buffer.put(17.toByte())   // Protocol: UDP
        buffer.putShort(0)        // Header checksum (set to 0)
        // Source IP (the DNS server we used)
        val dnsServerIp = InetAddress.getByName(dnsServer).address
        buffer.put(dnsServerIp)
        // Destination IP (the original client)
        buffer.put(destIp)

        // ----- UDP HEADER (8 bytes) -----
        buffer.putShort(53.toShort())          // Source port (DNS)
        buffer.putShort(destPort.toShort())    // Destination port (client)
        buffer.putShort((8 + dnsResponse.size).toShort()) // UDP length
        buffer.putShort(0)                     // UDP checksum (optional)

        // ----- DNS PAYLOAD -----
        buffer.put(dnsResponse)

        return buffer.array()
    }

    fun stop() {
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
        try {
            dnsSocket.close()
        } catch (_: Exception) { }
        try {
            tunnelSocket.close()
        } catch (_: Exception) { }
    }
}
