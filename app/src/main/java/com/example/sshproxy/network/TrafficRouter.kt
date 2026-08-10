package com.example.sshproxy.network

import android.util.Log
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
    private val TAG = "TrafficRouter"
    private var isRunning = false
    private val KEEP_ALIVE_INTERVAL = 5000L  // 5 seconds

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

                    LogManager.addLog("TUN FD: $tunFileDescriptor, Socket: ${tunnelSocket.remoteSocketAddress}")

                    // ============================================================
                    // KEEP‑ALIVE THREAD (sends a tiny packet every 5 seconds)
                    // ============================================================
                    val keepAliveThread = Thread {
                        while (isRunning) {
                            try {
                                Thread.sleep(KEEP_ALIVE_INTERVAL)
                                if (isRunning && tunnelSocket.isConnected && !tunnelSocket.isClosed) {
                                    // Send a single space byte to keep the proxy/SSH alive
                                    tunnelOutput.write(32)
                                    tunnelOutput.flush()
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                    keepAliveThread.start()

                    // ============================================================
                    // READ THREAD (TUN → SSH)
                    // ============================================================
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
                                }
                                break
                            }
                        }
                        LogManager.addLog("Read thread stopped")
                    }

                    // ============================================================
                    // WRITE THREAD (SSH → TUN)
                    // ============================================================
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
                    keepAliveThread.join()

                } catch (e: Exception) {
                    LogManager.addLog("Router thread error: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            LogManager.addLog("FATAL ERROR in TrafficRouter.start(): ${e.message}")
        }
    }

    fun stop() {
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
        try {
            tunnelSocket.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
