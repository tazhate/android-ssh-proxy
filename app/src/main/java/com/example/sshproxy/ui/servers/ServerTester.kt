package com.example.sshproxy.ui.servers

import android.content.Context
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.network.HttpLatencyTester
import com.example.sshproxy.network.ConnectionQuality
import com.example.sshproxy.security.KnownHostsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

class ServerTester(private val context: Context) {

    private val serverRepository = ServerRepository(context)
    private val keyRepository = KeyRepository(context)
    private val preferencesManager = PreferencesManager(context)

    suspend fun test(server: Server): ServerTestResult = withContext(Dispatchers.IO) {
        var sshClient: SSHClient? = null
        var localPortForwarder: LocalPortForwarder? = null
        var serverSocket: ServerSocket? = null

        try {
            // 1. Establish SSH connection
            sshClient = SSHClient()
            sshClient.addHostKeyVerifier(ServerTestHostKeyVerifier(context))
            sshClient.connect(server.host, server.port)

            val keyFile = resolvePrivateKeyFile(server.sshKeyId)
                ?: throw IOException("SSH key not found. Please generate one.")

            val keyProvider = PKCS8KeyFile()
            keyProvider.init(keyFile)
            sshClient.authPublickey(server.username, keyProvider)

            // 2. Setup local port forwarder
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val localPort = serverSocket.localPort
            val params = Parameters("127.0.0.1", localPort, "127.0.0.1", 8118)
            localPortForwarder = sshClient.newLocalPortForwarder(params, serverSocket)

            val forwarderJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    localPortForwarder.listen()
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // 3. Run HttpLatencyTester
            val latencyTester = HttpLatencyTester(
                proxyHost = "127.0.0.1",
                proxyPort = localPort,
                timeoutMs = 5000
            )
            val result = latencyTester.performSingleTest()

            forwarderJob.cancel()

            val quality = when {
                result.averageLatencyMs > 2000 -> ConnectionQuality.POOR
                result.averageLatencyMs > 1000 -> ConnectionQuality.FAIR
                result.averageLatencyMs > 500 -> ConnectionQuality.GOOD
                else -> ConnectionQuality.EXCELLENT
            }

            ServerTestResult.success(server.id, result.averageLatencyMs, quality)
        } catch (e: Exception) {
            ServerTestResult.failure(server.id, e.message ?: "Unknown error")
        } finally {
            localPortForwarder?.close()
            serverSocket?.close()
            sshClient?.disconnect()
        }
    }

    private suspend fun resolvePrivateKeyFile(serverSshKeyId: String? = null): File? {
        val keyId = serverSshKeyId ?: preferencesManager.getActiveKeyId()
        if (keyId != null) {
            try {
                val privateKey = keyRepository.getPrivateKey(keyId)
                if (privateKey != null) {
                    val tempFile = File.createTempFile("ssh_key_$keyId", ".pem", context.cacheDir)
                    tempFile.deleteOnExit()
                    val pemContent = convertPrivateKeyToPem(privateKey)
                    tempFile.writeText(pemContent)
                    return tempFile
                } else {
                    return null
                }
            } catch (e: Exception) {
                return null
            }
        } else {
            return null
        }
    }

    private fun convertPrivateKeyToPem(privateKey: java.security.PrivateKey): String {
        val stringWriter = java.io.StringWriter()
        org.bouncycastle.util.io.pem.PemWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY", privateKey.encoded))
        }
        return stringWriter.toString()
    }
}

class ServerTestHostKeyVerifier(private val context: Context) : HostKeyVerifier {
    private val knownHostsManager = KnownHostsManager(context)

    override fun verify(hostname: String?, port: Int, key: java.security.PublicKey?): Boolean {
        if (hostname == null || key == null) {
            return false
        }
        val keyBytes = key.encoded
        val keyType = determineKeyType(key)
        when (knownHostsManager.validateHostKey(hostname, port, keyBytes, keyType)) {
            KnownHostsManager.HostKeyValidationResult.NEW_HOST -> {
                knownHostsManager.storeHostKey(hostname, port, keyBytes, keyType)
                return true
            }
            KnownHostsManager.HostKeyValidationResult.VALID -> {
                return true
            }
            KnownHostsManager.HostKeyValidationResult.KEY_CHANGED -> {
                knownHostsManager.storeHostKey(hostname, port, keyBytes, keyType)
                return true
            }
        }
    }

    private fun determineKeyType(key: java.security.PublicKey): String {
        return when (key.algorithm.lowercase()) {
            "rsa" -> "ssh-rsa"
            "ec" -> "ecdsa-sha2-nistp256"
            "eddsa", "ed25519" -> "ssh-ed25519"
            else -> "ssh-${key.algorithm.lowercase()}"
        }
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
        return mutableListOf()
    }
}