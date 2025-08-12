package com.example.sshproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.example.sshproxy.data.PreferencesManager
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.ServerSocket
import java.security.Security

class SshProxyService : VpnService() {
    private var sshClient: SSHClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var portForwarder: LocalPortForwarder? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val localProxyPort = 8080
    private val remoteProxyPort = 8118 // HTTP proxy port on remote server (e.g., Privoxy)
    private var portForwardingJob: Job? = null
    private var serverSocket: ServerSocket? = null

    companion object {
        private const val TAG = "SshProxyService"
        val isRunning = MutableLiveData<Boolean>().apply { value = false }

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        val action = intent.action
        if (SERVICE_INTERFACE == action) {
            return super.onBind(intent)
        }
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SshProxyService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_PROXY") {
            stopProxy()
            return START_NOT_STICKY
        }
        if (isRunning.value == false) {
            val host = intent?.getStringExtra("HOST")
            val port = intent?.getIntExtra("PORT", 22)
            val user = intent?.getStringExtra("USER") ?: "user"
            if (host != null && port != null) {
                startProxy(host, port, user)
            } else {
                Log.e(TAG, "Host or port not provided in intent")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startProxy(host: String, port: Int, user: String) {
        Log.d(TAG, "startProxy called for $user@$host:$port")
        AppLog.log("Connecting to $user@$host:$port...")
        isRunning.postValue(true)
        serviceScope.launch {
            try {
                // 1. Connect to SSH
                setupSshConnection(host, port, user)

                val forwarderReady = CompletableDeferred<Unit>()

                // 2. Start the listener in a background job
                portForwardingJob = launch(Dispatchers.IO) {
                    try {
                        AppLog.log("Opening local proxy port $localProxyPort...")
                        serverSocket = ServerSocket(localProxyPort)
                        
                        // Signal that the socket is open and ready
                        forwarderReady.complete(Unit)

                        portForwarder = sshClient?.newLocalPortForwarder(
                            Parameters("127.0.0.1", localProxyPort, "127.0.0.1", remoteProxyPort),
                            serverSocket!!
                        )
                        AppLog.log("Port forwarding established. Listening...")
                        portForwarder?.listen() // This blocks
                    } catch (e: Exception) {
                        if (isActive) {
                            if (!forwarderReady.isCompleted) {
                                forwarderReady.completeExceptionally(e)
                            }
                            Log.e(TAG, "Port forwarding error", e)
                            AppLog.log("Error: Port forwarding failed - ${e.message}")
                            stopProxy()
                        }
                    }
                }

                // 3. Wait until the listener is confirmed to be ready
                AppLog.log("Waiting for local proxy to be ready...")
                forwarderReady.await()
                AppLog.log("Local proxy is ready.")

                // 4. Establish the VPN
                establishVpn()
                showNotification()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                AppLog.log("Error: ${e.javaClass.simpleName} - ${e.message}")
                stopProxy()
            }
        }
    }

    private suspend fun setupSshConnection(host: String, port: Int, user: String) = withContext(Dispatchers.IO) {
        try {
            AppLog.log("Resolving SSH key...")
            val keyFile = resolvePrivateKeyFile()
            if (keyFile == null) {
                AppLog.log("No private key found (active or legacy). Aborting.")
                throw IllegalStateException("Private key missing")
            } else {
                AppLog.log("Using key file: ${keyFile.name}")
            }

            AppLog.log("Initializing SSH client...")
            sshClient = SSHClient().apply {
                addHostKeyVerifier(PromiscuousVerifier())
                AppLog.log("Connecting to $host:$port...")
                try {
                    connect(host, port)
                } catch (e: UnknownHostException) {
                    AppLog.log("DNS error: ${e.message}")
                    throw e
                } catch (e: ConnectException) {
                    AppLog.log("Connection refused: ${e.message}")
                    throw e
                } catch (e: SocketTimeoutException) {
                    AppLog.log("Connection timeout")
                    throw e
                }
                AppLog.log("Connected. Authenticating as '$user'...")
                val pkFile = PKCS8KeyFile()
                pkFile.init(keyFile)
                try {
                    authPublickey(user, pkFile)
                } catch (e: UserAuthException) {
                    AppLog.log("Auth failed: ${e.message}")
                    throw e
                }
                Log.d(TAG, "SSH authentication successful for user: $user")
                AppLog.log("SSH authentication successful.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSH connection failed", e)
            if (e is IllegalStateException) {
                // already logged
            } else {
                AppLog.log("SSH failure: ${e::class.simpleName}: ${e.message}")
            }
            throw e
        }
    }

    private fun resolvePrivateKeyFile(): File? {
        val prefs = PreferencesManager(this)
        val activeId = prefs.getActiveKeyId()
        if (activeId != null) {
            val multi = File(filesDir, "ssh_private_$activeId")
            if (multi.exists()) return multi
            AppLog.log("Active key id '$activeId' not found, fallback to legacy.")
        }
        val legacy = File(filesDir, "ssh_private_key")
        return if (legacy.exists()) legacy else null
    }

    private fun establishVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppLog.log("Preparing VPN...")

            // Check if another VPN is active or if permission is needed
            if (VpnService.prepare(this) != null) {
                AppLog.log("VPN permission not granted or another VPN is active. Stopping.")
                stopProxy()
                return
            }

            val builder = Builder()
            AppLog.log("Setting VPN session name...")
            builder.setSession(getString(R.string.app_name))

            // We need to add at least one address to make the VPN valid
            // Using a dummy address that won't conflict with real networks
            AppLog.log("Adding dummy VPN address...")
            builder.addAddress("10.255.255.254", 32)
            
            // Add an empty route to avoid capturing any real traffic through the tunnel
            // The HTTP proxy will handle the actual traffic
            AppLog.log("Setting MTU...")
            builder.setMtu(1500)

            // Set our local forwarder as the HTTP proxy for the VPN
            // This is only supported on Android 10 (API 29) and above
            AppLog.log("Setting HTTP proxy to 127.0.0.1:$localProxyPort...")
            val proxyInfo = ProxyInfo.buildDirectProxy("127.0.0.1", localProxyPort)
            builder.setHttpProxy(proxyInfo)
            AppLog.log("HTTP proxy set.")

            AppLog.log("Establishing VPN interface...")
            try {
                vpnInterface = builder.establish()
            } catch (e: Exception) {
                AppLog.log("Error establishing VPN: ${e.message}")
                Log.e(TAG, "Exception during builder.establish()", e)
                stopProxy()
                return
            }

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface (returned null).")
                AppLog.log("Failed to establish VPN interface (returned null).")
                stopProxy()
            } else {
                Log.d(TAG, "VPN established, proxy is set system-wide.")
                AppLog.log("VPN established, proxy is set system-wide.")
            }
        } else {
            // For older Android versions, VpnService does not support HTTP proxy.
            Log.e(TAG, "Automatic proxy setup via VpnService is not supported on Android versions below 10 (API 29). Stopping service.")
            AppLog.log("Automatic proxy setup is not supported on this Android version.")
            // We must stop, as we cannot fulfill the request.
            stopProxy()
        }
    }

    private fun stopProxy() {
        if (isRunning.value == false) return
        Log.d(TAG, "Stopping proxy")
        AppLog.log("Disconnecting...")
        isRunning.postValue(false)
        
        portForwardingJob?.cancel() // Cancel the listener job

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    vpnInterface?.close()
                    vpnInterface = null
                    portForwarder?.close()
                    serverSocket?.close()
                    sshClient?.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during resource cleanup", e)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "proxy_service",
                "SSH Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val stopIntent = Intent(this, SshProxyService::class.java).apply { action = "STOP_PROXY" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "proxy_service")
            .setContentTitle("SSH Proxy Active")
            .setContentText("System-wide proxy is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
        
        startForeground(1, notification)
    }

    override fun onDestroy() {
        if (isRunning.value == true) stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }
}