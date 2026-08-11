package com.example.sshproxy.network

import android.util.Log
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import com.example.sshproxy.LogManager
import org.json.JSONObject

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
    private val DOH_URL = "https://cloudflare-dns.com/dns-query"

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
                                        try {
                                            handleDnsOverHttps(buffer, len, outputStream)
                                        } catch (e: Exception) {
                                            LogManager.addLog("[DNS] DoH error: ${e.message} – skipping")
                                        }
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

    private fun handleDnsOverHttps(data: ByteArray, len: Int, tunOutput: FileOutputStream) {
        val srcIp = data.copyOfRange(12, 16)
        val srcPort = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val dnsQuery = data.copyOfRange(28, len)

        val (id, flags, qdcount, qname, qtype, qclass) = parseDnsQuery(dnsQuery)

        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(dnsQuery)
        val url = URL("$DOH_URL?dns=$encoded")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            LogManager.addLog("[DNS] DoH HTTP error: $responseCode")
            return
        }

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val json = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            json.append(line)
        }
        reader.close()

        val obj = JSONObject(json.toString())
        val answerArray = obj.optJSONArray("Answer")
        if (answerArray == null || answerArray.length() == 0) {
            LogManager.addLog("[DNS] No answer for $qname")
            val emptyResponse = buildDnsResponse(id, flags, qdcount, qname, qtype, qclass, emptyList())
            tunOutput.write(buildUdpPacket(srcIp, srcPort, emptyResponse))
            tunOutput.flush()
            return
        }

        val answers = mutableListOf<Triple<String, Int, String>>()
        for (i in 0 until answerArray.length()) {
            val ans = answerArray.getJSONObject(i)
            val name = ans.getString("name")
            val type = ans.getInt("type")
            val data = ans.getString("data")
            answers.add(Triple(name, type, data))
        }

        LogManager.addLog("[DNS] Resolved $qname → ${answers.map { it.third }.joinToString()}")

        val response = buildDnsResponse(id, flags, qdcount, qname, qtype, qclass, answers)
        val udpPacket = buildUdpPacket(srcIp, srcPort, response)
        tunOutput.write(udpPacket)
        tunOutput.flush()
        LogManager.addLog("[DNS] DoH response sent (${answers.size} answers)")
    }

    private fun parseDnsQuery(data: ByteArray): DnsQueryResult {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val id = bb.short
        val flags = bb.short
        val qdcount = bb.short
        val qname = StringBuilder()
        var len = bb.get().toInt() and 0xFF
        while (len != 0) {
            if (len > 0) {
                val label = ByteArray(len)
                bb.get(label)
                qname.append(String(label)).append(".")
                len = bb.get().toInt() and 0xFF
            } else {
                break
            }
        }
        if (qname.isNotEmpty()) qname.deleteCharAt(qname.length - 1)
        val qtype = bb.short
        val qclass = bb.short
        return DnsQueryResult(id, flags, qdcount, qname.toString(), qtype, qclass)
    }
    private data class DnsQueryResult(
        val id: Short,
        val flags: Short,
        val qdcount: Short,
        val qname: String,
        val qtype: Short,
        val qclass: Short
    )

    private fun buildDnsResponse(
        id: Short,
        originalFlags: Short,
        qdcount: Short,
        qname: String,
        qtype: Short,
        qclass: Short,
        answers: List<Triple<String, Int, String>>
    ): ByteArray {
        val flags = 0x8180.toShort()
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)

        dos.writeShort(id.toInt())
        dos.writeShort(flags.toInt())
        dos.writeShort(qdcount.toInt())
        dos.writeShort(answers.size)
        dos.writeShort(0)
        dos.writeShort(0)

        val qnameBytes = encodeDomainName(qname)
        dos.write(qnameBytes)
        dos.writeShort(qtype.toInt())
        dos.writeShort(qclass.toInt())

        for ((name, type, data) in answers) {
            dos.writeShort(0xc00c) // pointer to QNAME
            dos.writeShort(type.toShort().toInt())
            dos.writeShort(qclass.toInt())
            dos.writeInt(300) // TTL
            val rdata = when (type) {
                1 -> InetAddress.getByName(data).address
                28 -> InetAddress.getByName(data).address
                else -> data.toByteArray()
            }
            dos.writeShort(rdata.size)
            dos.write(rdata)
        }

        dos.flush()
        return baos.toByteArray()
    }

    private fun encodeDomainName(name: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val labels = name.split(".")
        for (label in labels) {
            baos.write(label.length)
            baos.write(label.toByteArray())
        }
        baos.write(0)
        return baos.toByteArray()
    }

    private fun buildUdpPacket(destIp: ByteArray, destPort: Int, payload: ByteArray): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

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
        buffer.putShort((8 + payload.size).toShort())
        buffer.putShort(0)
        buffer.put(payload)

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
