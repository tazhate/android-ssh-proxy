package com.example.sshproxy.network

import com.example.sshproxy.LogManager
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Efficient packet router with buffer pooling and proper thread management.
 */
class TrafficRouter(
    private val tunFileDescriptor: FileDescriptor,
    private val tunnelSocket: Socket,
    private val sendBufferSize: Int = 16384,
    private val receiveBufferSize: Int = 32768
) {
    private val isRunning = AtomicBoolean(true)
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private val keepAliveInterval = 3000L

    // Buffer pooling
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private val poolSize = 10

    init {
        // Pre-allocate buffers
        repeat(poolSize) {
            bufferPool.add(ByteArray(receiveBufferSize))
        }
    }

    fun start() {
        LogManager.addLog("TrafficRouter starting")
        try {
            tunnelSocket.soTimeout = 30000
            tunnelSocket.tcpNoDelay = true
            tunnelSocket.keepAlive = true

            readThread = Thread {
                readFromTunnel()
            }
            writeThread = Thread {
                writeToTunnel()
            }

            readThread?.start()
            writeThread?.start()

            // Keep-alive thread
            Thread {
                while (isRunning.get()) {
                    try {
                        Thread.sleep(keepAliveInterval)
                        if (isRunning.get() && tunnelSocket.isConnected && !tunnelSocket.isClosed) {
                            tunnelSocket.getOutputStream().write(32) // space char
                            tunnelSocket.getOutputStream().flush()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }.start()

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] TrafficRouter start failed: ${e.message}")
            stop()
        }
    }

    private fun readFromTunnel() {
        LogManager.addLog("Read thread started")
        val inputStream = FileInputStream(tunFileDescriptor)
        val outputStream = tunnelSocket.getOutputStream()
        var buffer: ByteArray? = null

        while (isRunning.get()) {
            try {
                buffer = acquireBuffer()
                val len = inputStream.read(buffer)
                if (len > 0) {
                    outputStream.write(buffer, 0, len)
                    outputStream.flush()
                }
                releaseBuffer(buffer)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    LogManager.addLog("[ERROR] Read thread: ${e.message}")
                }
                break
            } finally {
                // Ensure buffer is released even on exception
                if (buffer != null) releaseBuffer(buffer)
            }
        }
        LogManager.addLog("Read thread stopped")
    }

    private fun writeToTunnel() {
        LogManager.addLog("Write thread started")
        val outputStream = FileOutputStream(tunFileDescriptor)
        val inputStream = tunnelSocket.getInputStream()
        var buffer: ByteArray? = null

        while (isRunning.get()) {
            try {
                buffer = acquireBuffer()
                val len = inputStream.read(buffer)
                if (len > 0) {
                    outputStream.write(buffer, 0, len)
                    outputStream.flush()
                }
                releaseBuffer(buffer)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    LogManager.addLog("[ERROR] Write thread: ${e.message}")
                }
                break
            } finally {
                if (buffer != null) releaseBuffer(buffer)
            }
        }
        LogManager.addLog("Write thread stopped")
    }

    private fun acquireBuffer(): ByteArray {
        return bufferPool.poll() ?: ByteArray(receiveBufferSize)
    }

    private fun releaseBuffer(buffer: ByteArray) {
        if (bufferPool.size < poolSize) {
            bufferPool.add(buffer)
        }
        // Otherwise discard; we have enough buffers
    }

    fun stop() {
        isRunning.set(false)
        readThread?.interrupt()
        writeThread?.interrupt()
        try {
            tunnelSocket.close()
        } catch (_: Exception) { }
        LogManager.addLog("TrafficRouter stopped")
    }
}