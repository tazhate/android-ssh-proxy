package com.example.sshproxy.network

import android.net.VpnService
import kotlinx.coroutines.*
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
    private var readJob: Job? = null
    private var writeJob: Job? = null

    fun start() {
        isRunning = true
        val inputStream = FileInputStream(tunFileDescriptor)
        val outputStream = FileOutputStream(tunFileDescriptor)
        val tunnelInput = tunnelSocket.getInputStream()
        val tunnelOutput = tunnelSocket.getOutputStream()

        // Read from TUN and write to SSH tunnel
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(32767)
            while (isRunning) {
                try {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        tunnelOutput.write(buffer, 0, length)
                        tunnelOutput.flush()
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }

        // Read from SSH tunnel and write to TUN
        writeJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(32767)
            while (isRunning) {
                try {
                    val length = tunnelInput.read(buffer)
                    if (length > 0) {
                        outputStream.write(buffer, 0, length)
                        outputStream.flush()
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        readJob?.cancel()
        writeJob?.cancel()
    }
}
