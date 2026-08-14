package com.example.sshproxy.network

import com.example.sshproxy.LogManager
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TrafficRouter(
    private val tunFileDescriptor: FileDescriptor,
    private val inputStream: InputStream,   // from SSH channel or socket
    private val outputStream: OutputStream, // from SSH channel or socket
    private val sendBufferSize: Int = 16384,
    private val receiveBufferSize: Int = 32768
) {
    private val isRunning = AtomicBoolean(true)
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private val keepAliveInterval = 2000L // 2 seconds

    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private val poolSize = 10

    init {
        repeat(poolSize) {
            bufferPool.add(ByteArray(receiveBufferSize))
        }
    }

    fun start() {
        LogManager.addLog("TrafficRouter starting")
        try {
            readThread = Thread { readFromTun() }
            writeThread = Thread { writeToTun() }

            readThread?.start()
            writeThread?.start()

            // Keep-alive thread: send a space every 2 seconds to keep the socket/channel alive
            Thread {
                try {
                    // First keep-alive after 1 second
                    Thread.sleep(1000)
                    while (isRunning.get()) {
                        if (isRunning.get()) {
                            try {
                                outputStream.write(32)
                                outputStream.flush()
                            } catch (_: Exception) { }
                        }
                        Thread.sleep(keepAliveInterval)
                    }
                } catch (_: Exception) { }
            }.start()

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] TrafficRouter start failed: ${e.message}")
            stop()
        }
    }

    private fun readFromTun() {
        LogManager.addLog("Read thread started")
        val tunInput = FileInputStream(tunFileDescriptor)
        var buffer: ByteArray? = null

        while (isRunning.get()) {
            try {
                buffer = acquireBuffer()
                val len = tunInput.read(buffer)
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
                if (buffer != null) releaseBuffer(buffer)
            }
        }
        LogManager.addLog("Read thread stopped")
    }

    private fun writeToTun() {
        LogManager.addLog("Write thread started")
        val tunOutput = FileOutputStream(tunFileDescriptor)
        var buffer: ByteArray? = null

        while (isRunning.get()) {
            try {
                buffer = acquireBuffer()
                val len = inputStream.read(buffer)
                if (len > 0) {
                    tunOutput.write(buffer, 0, len)
                    tunOutput.flush()
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
    }

    fun stop() {
        isRunning.set(false)
        readThread?.interrupt()
        writeThread?.interrupt()
        try {
            inputStream.close()
            outputStream.close()
        } catch (_: Exception) { }
        LogManager.addLog("TrafficRouter stopped")
    }
}
