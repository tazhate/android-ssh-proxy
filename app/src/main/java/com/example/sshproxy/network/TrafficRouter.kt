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
    private val dnsServer: String = "1.1.1.1",
    private val sendBufferSize: Int = 16384,
    private val receiveBufferSize: Int = 32768
) {
    private val TAG = "TrafficRouter"
    private var isRunning = false
    private val KEEP_ALIVE_INTERVAL = 3000L
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
                    // Use the larger of the two buffers for reading from TUN
                    val bufferSize = maxOf(sendBufferSize, receiveBufferSize)
                    val buffer = ByteArray(bufferSize)

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

                    val readThread = Thread {
                        LogManager.addLog("Read thread started")
                        while (isRunning) {
                            try {
                                val len = inputStream.read(buffer)
                                if (len > 0) {
                                    if (isDnsQuery(buffer, len)) {
                                        handleDnsQuery(buffer, len, outputStream)
                                    } else {
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

    private fun isDnsQuery(data: ByteArray, len: Int): Boolean {
        if (len < 28) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return false
        val dstPort = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
        return dstPort == 53
    }

    private fun handleDnsQuery(data: ByteArray, len: Int, tunOutput: FileOutputStream) {
        try {
            val srcIp = data.copyOfRange(12, 16)
            val srcPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
            val dnsData = data.copyOfRange(28, len)

            val dnsServerAddr = InetAddress.getByName(dnsServer)
            val packet = DatagramPacket(dnsData, dnsData.size, dnsServerAddr, dnsPort)
            dnsSocket.send(packet)

            dnsSocket.soTimeout = 5000
            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            dnsSocket.receive(responsePacket)

            val responseData = responsePacket.data.copyOf(responsePacket.length)
            val outPacket = buildUdpResponsePacket(srcIp, srcPort, responseData)
            tunOutput.write(outPacket)
            tunOutput.flush()
            LogManager.addLog("[DNS] Forwarded query, response size: ${responseData.size} bytes")
        } catch (e: Exception) {
            LogManager.addLog("[DNS] Error: ${e.message}")
        }
    }

    private fun buildUdpResponsePacket(
        destIp: ByteArray,
        destPort: Int,
        dnsResponse: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + dnsResponse.size
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(0x45.toByte())
        buffer.put(0.toByte())
        buffer.putShort(totalLen.toShort())
        buffer.putShort(System.currentTimeMillis().toShort())
        buffer.putShort(0)
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0)
        val dnsServerIp = InetAddress.getByName(dnsServer).address
        buffer.put(dnsServerIp)
        buffer.put(destIp)

        buffer.putShort(53.toShort())
        buffer.putShort(destPort.toShort())
        buffer.putShort((8 + dnsResponse.size).toShort())
        buffer.putShort(0)

        buffer.put(dnsResponse)
        return buffer.array()
    }

    fun stop() {
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
        try { dnsSocket.close() } catch (_: Exception) { }
        try { tunnelSocket.close() } catch (_: Exception) { }
    }
}
