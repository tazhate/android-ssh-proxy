fun start() {
    Log.d(TAG, "TrafficRouter start() called")
    isRunning = true
    Thread {
        try {
            val inputStream = FileInputStream(tunFileDescriptor)
            val outputStream = FileOutputStream(tunFileDescriptor)
            val tunnelInput = tunnelSocket.getInputStream()
            val tunnelOutput = tunnelSocket.getOutputStream()

            Log.d(TAG, "TUN FD: $tunFileDescriptor, Socket: ${tunnelSocket.remoteSocketAddress}")
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
                        if (isRunning) {
                            Log.e(TAG, "Read thread error", e)
                        } else {
                            Log.d(TAG, "Read thread stopped due to isRunning = false")
                        }
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
                        if (isRunning) {
                            Log.e(TAG, "Write thread error", e)
                        } else {
                            Log.d(TAG, "Write thread stopped due to isRunning = false")
                        }
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
