package com.example.sshproxy.network

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import com.example.sshproxy.LogManager

class TrafficRouter(
    private val vpnService: android.net.VpnService,
    private val tunFileDescriptor: FileDescriptor,
    private val tunnelSocket: Socket
) {
    private var isRunning = false

    fun start() {
        LogManager.addLog("TrafficRouter start() called")
        isRunning = true
        Thread {
            try {
                val inputStream = FileInputStream(tunFileDescriptor)
                val outputStream = FileOutputStream(tunFileDescriptor)
                val tunnelInput = tunnelSocket.getInputStream()
                val tunnelOutput = tunnelSocket.getOutputStream()
                val buffer = ByteArray(32767)

                LogManager.addLog("TUN FD: $tunFileDescriptor, Socket: ${tunnelSocket.remoteSocketAddress}")
                LogManager.addLog("Starting router threads")

                val readThread = Thread {
                    LogManager.addLog("Read thread started")
                    while (isRunning) {
                        try {
                            val len = inputStream.read(buffer)
                            if (len > 0) {
                                tunnelOutput.write(buffer, 0, len)
                                tunnelOutput.flush()
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                LogManager.addLog("Read thread error: ${e.message}")
                            } else {
                                LogManager.addLog("Read thread stopped due to isRunning = false")
                            }
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
                            if (isRunning) {
                                LogManager.addLog("Write thread error: ${e.message}")
                            } else {
                                LogManager.addLog("Write thread stopped due to isRunning = false")
                            }
                            break
                        }
                    }
                    LogManager.addLog("Write thread stopped")
                }

                readThread.start()
                writeThread.start()
                readThread.join()
                writeThread.join()

            } catch (e: Exception) {
                LogManager.addLog("Router error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
    }
}
