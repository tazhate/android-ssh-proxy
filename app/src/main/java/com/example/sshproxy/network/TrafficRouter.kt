package com.example.sshproxy.network

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket

class TrafficRouter(
    private val vpnService: VpnService,
    private val tunFileDescriptor: FileDescriptor,
    private val tunnelSocket: Socket
) {
    private var isRunning = false
    private val TAG = "TrafficRouter"

    fun start() {
        Log.d(TAG, "TrafficRouter start() called")
        isRunning = true
        Thread {
            try {
                val inputStream = FileInputStream(tunFileDescriptor)
                val outputStream = FileOutputStream(tunFileDescriptor)
                val tunnelInput = tunnelSocket.getInputStream()
                val tunnelOutput = tunnelSocket.getOutputStream()
                val buffer = ByteArray(32767)

                Log.d(TAG, "TUN FD: $tunFileDescriptor, Socket: $tunnelSocket")
                Log.d(TAG, "Starting router threads")

                val readThread = Thread {
                    Log.d(TAG, "Read thread started")
                    while (isRunning) {
                        try {
                            val len = inputStream.read(buffer)
                            if (len > 0) {
                                tunnelOutput.write(buffer, 0, len)
                                tunnelOutput.flush()
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG, "Wrote $len bytes to tunnel")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Read thread error", e)
                            break
                        }
                    }
                    Log.d(TAG, "Read thread stopped")
                }

                val writeThread = Thread {
                    Log.d(TAG, "Write thread started")
                    while (isRunning) {
                        try {
                            val len = tunnelInput.read(buffer)
                            if (len > 0) {
                                outputStream.write(buffer, 0, len)
                                outputStream.flush()
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG, "Wrote $len bytes to TUN")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Write thread error", e)
                            break
                        }
                    }
                    Log.d(TAG, "Write thread stopped")
                }

                readThread.start()
                writeThread.start()
                readThread.join()
                writeThread.join()

            } catch (e: Exception) {
                Log.e(TAG, "Router error", e)
            }
        }.start()
    }

    fun stop() {
        Log.d(TAG, "TrafficRouter stop() called")
        isRunning = false
    }
}
