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

    fun start() {
        try {
            Log.d(TAG, "🔥🔥🔥 TrafficRouter.start() CALLED! 🔥🔥🔥")
            LogManager.addLog("TrafficRouter start() called")

            isRunning = true
            Thread {
                try {
                    LogManager.addLog("Router thread started")
                    Log.d(TAG, "Router thread started")

                    val inputStream = FileInputStream(tunFileDescriptor)
                    val outputStream = FileOutputStream(tunFileDescriptor)
                    val tunnelInput = tunnelSocket.getInputStream()
                    val tunnelOutput = tunnelSocket.getOutputStream()
                    val buffer = ByteArray(32767)

                    Log.d(TAG, "TUN FD: $tunFileDescriptor, Socket: ${tunnelSocket.remoteSocketAddress}")
                    LogManager.addLog("TUN FD: $tunFileDescriptor, Socket: ${tunnelSocket.remoteSocketAddress}")
                    Log.d(TAG, "Starting router threads")
                    LogManager.addLog("Starting router threads")

                    val readThread = Thread {
                        Log.d(TAG, "Read thread started")
                        LogManager.addLog("Read thread started")
                        while (isRunning) {
                            try {
                                val len = inputStream.read(buffer)
                                if (len > 0) {
                                    tunnelOutput.write(buffer, 0, len)
                                    tunnelOutput.flush()
                                    Log.d(TAG, "Read thread: wrote $len bytes to tunnel")
                                }
                            } catch (e: Exception) {
                                if (isRunning) {
                                    Log.e(TAG, "Read thread error", e)
                                    LogManager.addLog("Read thread error: ${e.message}")
                                } else {
                                    Log.d(TAG, "Read thread stopped due to isRunning = false")
                                    LogManager.addLog("Read thread stopped due to isRunning = false")
                                }
                                break
                            }
                        }
                        Log.d(TAG, "Read thread stopped")
                        LogManager.addLog("Read thread stopped")
                    }

                    val writeThread = Thread {
                        Log.d(TAG, "Write thread started")
                        LogManager.addLog("Write thread started")
                        while (isRunning) {
                            try {
                                val len = tunnelInput.read(buffer)
                                if (len > 0) {
                                    outputStream.write(buffer, 0, len)
                                    outputStream.flush()
                                    Log.d(TAG, "Write thread: wrote $len bytes to TUN")
                                }
                            } catch (e: Exception) {
                                if (isRunning) {
                                    Log.e(TAG, "Write thread error", e)
                                    LogManager.addLog("Write thread error: ${e.message}")
                                } else {
                                    Log.d(TAG, "Write thread stopped due to isRunning = false")
                                    LogManager.addLog("Write thread stopped due to isRunning = false")
                                }
                                break
                            }
                        }
                        Log.d(TAG, "Write thread stopped")
                        LogManager.addLog("Write thread stopped")
                    }

                    readThread.start()
                    writeThread.start()
                    readThread.join()
                    writeThread.join()

                } catch (e: Exception) {
                    Log.e(TAG, "Router thread error", e)
                    LogManager.addLog("Router thread error: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL ERROR in start()", e)
            LogManager.addLog("FATAL ERROR in TrafficRouter.start(): ${e.message}")
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called")
        LogManager.addLog("TrafficRouter stop() called")
        isRunning = false
    }
}
