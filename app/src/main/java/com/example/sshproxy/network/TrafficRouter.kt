package com.example.sshproxy.network

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.sshproxy.LogManager

class TrafficRouter(
    private val vpnService: android.net.VpnService,
    private val tunFileDescriptor: FileDescriptor,
    private val tunnelSocket: Socket,
    private val dnsServer: String = "1.1.1.1",
    private val sendBufferSize: Int = 16384,
    private val receiveBufferSize: Int = 32768
) {
    private val TAG = "TrafficRouter"
    private var isRunning = false
    private val KEEP_ALIVE_INTERVAL = 3000L

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
                    val bufferSize = maxOf(sendBufferSize, receiveBufferSize)
                    val buffer = ByteArray(bufferSize)

                    // Keep-alive thread (space char every 3s)
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
                                        handleDnsQueryOverTcp(buffer, len, outputStream, tunnelOutput)
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

    private fun isDnsQuery(data: ByteArray, len: Int): Boolean {
        if (len < 28) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return false
        val dstPort = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
        return dstPort == 53
    }

    /**
     * Handle DNS query via TCP through the existing SSH socket.
     * RFC 1035: DNS over TCP uses a 2-byte length prefix (network order) followed by the DNS message.
     */
    private fun handleDnsQueryOverTcp(data: ByteArray, len: Int, tunOutput: FileOutputStream, sshOutput: java.io.OutputStream) {
        try {
            // Extract source IP and port from IP header
            val srcIp = data.copyOfRange(12, 16) // 4 bytes
            val srcPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            // DNS payload starts after IP header (20) + UDP header (8) = 28 bytes
            val dnsData = data.copyOfRange(28, len)

            // Build TCP frame: 2-byte length (big-endian) + DNS data
            val tcpFrame = ByteBuffer.allocate(2 + dnsData.size)
            tcpFrame.order(ByteOrder.BIG_ENDIAN)
            tcpFrame.putShort(dnsData.size.toShort())
            tcpFrame.put(dnsData)

            // Send DNS query over TCP via the SSH socket
            sshOutput.write(tcpFrame.array())
            sshOutput.flush()
            LogManager.addLog("[DNS] Sent TCP query (${dnsData.size} bytes)")

            // Read the TCP response from the SSH socket
            // First 2 bytes = response length
            val lenBuffer = ByteArray(2)
            var read = 0
            while (read < 2) {
                val n = tunnelSocket.getInputStream().read(lenBuffer, read, 2 - read)
                if (n < 0) throw Exception("EOF reading length")
                read += n
            }
            val responseLen = ByteBuffer.wrap(lenBuffer).order(ByteOrder.BIG_ENDIAN).getShort().toInt()
            if (responseLen <= 0 || responseLen > 4096) {
                LogManager.addLog("[DNS] Invalid response length: $responseLen")
                return
            }

            val responseData = ByteArray(responseLen)
            read = 0
            while (read < responseLen) {
                val n = tunnelSocket.getInputStream().read(responseData, read, responseLen - read)
                if (n < 0) throw Exception("EOF reading response")
                read += n
            }

            // Build UDP response packet (IP header + UDP header + DNS response)
            val outPacket = buildUdpResponsePacket(srcIp, srcPort, responseData)
            tunOutput.write(outPacket)
            tunOutput.flush()
            LogManager.addLog("[DNS] Received TCP response (${responseData.size} bytes)")

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
        val totalLen = 20 + 8 + dnsResponse.size
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // ----- IP HEADER (20 bytes) -----
        buffer.put(0x45.toByte())
        buffer.put(0.toByte())
        buffer.putShort(totalLen.toShort())
        buffer.putShort(System.currentTimeMillis().toShort())
        buffer.putShort(0)
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0)
        // Source IP (the DNS server) – we set it to the configured DNS server
        val dnsServerIp = InetAddress.getByName(dnsServer).address
        buffer.put(dnsServerIp)
        buffer.put(destIp)

        // ----- UDP HEADER (8 bytes) -----
        buffer.putShort(53.toShort())          // Source port (DNS)
        buffer.putShort(destPort.toShort())    // Destination port (client)
        buffer.putShort((8 + dnsResponse.size).toShort())
        buffer.putShort(0)                     // UDP checksum (optional)

        // ----- DNS PAYLOAD -----
        buffer.put(dnsResponse)

        return buffer.array()
    }

    fun stop() {
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
        try {
            tunnelSocket.close()
        } catch (_: Exception) { }
    }
}
