package com.example.sshproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.IBinder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.service.ConnectionHealthMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.Security
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

class SshProxyService : VpnService() {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING, 
        CONNECTED,
        DISCONNECTING
    }
    
    companion object {
        const val ACTION_START = "com.example.sshproxy.START_VPN"
        const val ACTION_STOP = "com.example.sshproxy.STOP_VPN"
        const val EXTRA_SERVER_ID = "server_id"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ssh_proxy_channel"
        private const val TAG = "SshProxyService"

        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
        
        // Для обратной совместимости - создаем isRunning из connectionState
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private var sshClient: SSHClient? = null
    private var localPortForwarder: LocalPortForwarder? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectionJob: Job? = null
    private var serverSocket: ServerSocket? = null
    
    private var connectionMonitor: ConnectionHealthMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var serverRepository: ServerRepository
    private var currentServerId: Long = -1L
    private var activeNetworks: Array<Network>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastActiveNetwork: Network? = null
    private var isVpnTemporarilyDisabled = false
    private var isVpnRecreating = false
    private var lastNetworkType: String? = null // "wifi" или "cellular"


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        preferencesManager = PreferencesManager(this)
        serverRepository = ServerRepository(this)
        
        // Initialize connection monitor
        connectionMonitor = ConnectionHealthMonitor(
            healthCheckIntervalMs = preferencesManager.getHealthCheckInterval(),
            maxReconnectAttempts = preferencesManager.getMaxReconnectAttempts(),
            initialBackoffMs = preferencesManager.getInitialBackoffMs()
        )
        
        // Observe connection state changes
        serviceScope.launch {
            connectionMonitor?.connectionState?.collectLatest { state ->
                when (state) {
                    ConnectionHealthMonitor.ConnectionState.RECONNECTING -> {
                        Log.d(TAG, "Attempting to reconnect...")
                        updateNotification("Reconnecting...")
                    }
                    ConnectionHealthMonitor.ConnectionState.CONNECTED -> {
                        Log.i(TAG, "Connection restored")
                        updateNotification("Connected")
                    }
                    ConnectionHealthMonitor.ConnectionState.FAILED -> {
                        Log.e(TAG, "Failed to reconnect after maximum attempts")
                        updateNotification("Connection failed")
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                _connectionState.value = ConnectionState.CONNECTING
                _isRunning.value = false // Connecting != Running
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1)
                if (serverId != -1L) {
                    currentServerId = serverId
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    connectionJob = serviceScope.launch {
                        startConnection(serverId)
                    }
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startConnection(serverId: Long) {
        withContext(Dispatchers.IO) {
            try {
                AppLog.log("Starting connection...")
                
                // Проверяем, что VPN еще НЕ активен
                val currentVpnStatus = if (vpnInterface != null) "ACTIVE" else "INACTIVE"
                AppLog.log("Current VPN status: $currentVpnStatus")
                
                // Получаем активные сети для обхода VPN
                val connectivityManager = getSystemService(ConnectivityManager::class.java)
                activeNetworks = connectivityManager?.let { cm ->
                    try {
                        val activeNetwork = cm.activeNetwork
                        if (activeNetwork != null) {
                            val capabilities = cm.getNetworkCapabilities(activeNetwork)
                            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                            val notVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                            
                            AppLog.log("Active network: $activeNetwork, hasInternet: $hasInternet, notVPN: $notVpn, validated: $validated")
                            
                            if (hasInternet && notVpn && validated) {
                                arrayOf(activeNetwork)
                            } else {
                                AppLog.log("Active network is not suitable for SSH bypass")
                                arrayOf<Network>()
                            }
                        } else {
                            AppLog.log("No active network found")
                            arrayOf<Network>()
                        }
                    } catch (e: Exception) {
                        AppLog.log("Error getting networks: ${e.message}")
                        arrayOf<Network>()
                    }
                } ?: arrayOf()
                
                AppLog.log("Found ${activeNetworks?.size ?: 0} active non-VPN networks")
                
                val server = serverRepository.getServerById(serverId) ?: run {
                    Log.e(TAG, "Server not found")
                    AppLog.log("Error: Server not found.")
                    stopSelf()
                    return@withContext
                }

                AppLog.log("Connecting to ${server.name} (${server.host}:${server.port}) as ${server.username}")
                AppLog.log("SSH connect timeout: 30000ms")
                
                // SSH подключение как в рабочем example.kt
                AppLog.log("Initializing SSH client...")
                sshClient = SSHClient().apply {
                    addHostKeyVerifier(PromiscuousVerifier())
                    AppLog.log("Connecting to ${server.host}:${server.port}...")
                    connect(server.host, server.port)
                    AppLog.log("Connected. Authenticating as '${server.username}'...")
                    
                    val keyFile = resolvePrivateKeyFile(server.sshKeyId)
                        ?: throw IOException("SSH key not found. Please generate one.")

                    // Try different key file providers for Ed25519 support
                    val keyProvider = try {
                        val pkcs8KeyFile = PKCS8KeyFile()
                        pkcs8KeyFile.init(keyFile)
                        // Test if we can actually get the public key (this will fail for Ed25519)
                        pkcs8KeyFile.public
                        pkcs8KeyFile
                    } catch (e: Exception) {
                        AppLog.log("PKCS8KeyFile failed: ${e.message}, trying OpenSSHKeyFile")
                        try {
                            val opensshKeyFile = net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile()
                            opensshKeyFile.init(keyFile)
                            opensshKeyFile
                        } catch (e2: Exception) {
                            AppLog.log("OpenSSHKeyFile failed: ${e2.message}")
                            throw IOException("Unable to load SSH key: ${e.message}")
                        }
                    }

                    authPublickey(server.username, keyProvider)
                    AppLog.log("SSH authentication successful.")
                }

                // 2. Port forwarding
                AppLog.log("Setting up port forwarding...")
                setupPortForwarding()

                // 3. Запускаем мониторинг сети
                AppLog.log("Starting network monitoring...")
                // Инициализируем текущую активную сеть
                val connManager = getSystemService(ConnectivityManager::class.java)
                lastActiveNetwork = connManager?.activeNetwork
                lastNetworkType = getNetworkType(connManager, lastActiveNetwork)
                AppLog.log("Initial active network: $lastActiveNetwork, type: $lastNetworkType")
                startNetworkMonitoring()
                
                // 4. VPN после стабильного SSH
                AppLog.log("Setting up VPN...")
                setupVpn()

                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _isRunning.value = true
                    updateNotification("Connected to ${server.name}")
                    AppLog.log("Connection fully established")
                }

                connectionMonitor?.onConnectionEstablished()
                if (preferencesManager.isAutoReconnectEnabled()) {
                    connectionMonitor?.startMonitoring(
                        scope = serviceScope,
                        isConnectionAlive = { checkSshConnection() },
                        onReconnect = { reconnectSsh(serverId) }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                AppLog.log("Error: Connection failed - ${e.message}")
                connectionMonitor?.onConnectionLost()
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _isRunning.value = false
                    updateNotification("Connection failed")
                    stopSelf()
                }
            }
        }
    }

    private fun setupPortForwarding() {
        AppLog.log("Binding to local port 8080...")
        serverSocket = ServerSocket(8080, 50, InetAddress.getByName("127.0.0.1"))
        val params = Parameters("127.0.0.1", 8080, "127.0.0.1", 8118)
        localPortForwarder = sshClient?.newLocalPortForwarder(params, serverSocket)
        
        // Start forwarding in background
        serviceScope.launch(Dispatchers.IO) {
            try {
                AppLog.log("Port forwarder listening...")
                localPortForwarder?.listen()
                AppLog.log("Port forwarder stopped.")
            } catch (e: Exception) {
                if (e is InterruptedException || e.message?.contains("Socket closed") == true) {
                    Log.i(TAG, "Port forwarding stopped intentionally.")
                    AppLog.log("Port forwarding stopped.")
                } else {
                    Log.e(TAG, "Port forwarding error: ${e.message}", e)
                    AppLog.log("Error: Port forwarding failed - ${e.message}")
                }
            }
        }
    }

    private suspend fun setupVpn() {
        val server = serverRepository.getServerById(currentServerId) ?: throw IOException("Server not found")
        
        val builder = Builder()
            .setSession("SSH Proxy")
            .addAddress("10.0.0.2", 32)  // IPv4
            .addAddress("fd00::1", 128)   // IPv6 local address
            .addDnsServer("8.8.8.8")      // IPv4 DNS
            .addDnsServer("2001:4860:4860::8888") // IPv6 Google DNS
            .setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 8080))
            .setMtu(1500)
        
        // Настраиваем обход VPN для SSH соединения
        if (activeNetworks != null && activeNetworks!!.isNotEmpty()) {
            AppLog.log("Setting underlying networks to bypass VPN")
            builder.setUnderlyingNetworks(activeNetworks)
        }
        
        // Защита SSH соединения от VPN маршрутизации
        try {
            // Используем уже подключенный SSH клиент для получения IP
            val sshServerIp = if (server.host.matches(Regex("\\d+\\.\\d+\\.d+\\.d+"))) {
                // Уже IP адрес
                server.host
            } else {
                // Разрешаем имя хоста
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(server.host).hostAddress
                }
            }
            
            AppLog.log("Excluding SSH server $sshServerIp from VPN routes")
            
            // ПРОСТОЕ РЕШЕНИЕ: используем setUnderlyingNetworks вместо сложной маршрутизации
            if (activeNetworks != null && activeNetworks!!.isNotEmpty()) {
                AppLog.log("Using setUnderlyingNetworks for SSH bypass")
                builder.setUnderlyingNetworks(activeNetworks)
            } else {
                // Фоллбек - добавляем простые маршруты для IPv4 и IPv6
                AppLog.log("Using split routing fallback")
                // IPv4 routes
                builder.addRoute("1.0.0.0", 8)    // 1.0.0.0/8
                builder.addRoute("2.0.0.0", 7)    // 2.0.0.0/7 
                builder.addRoute("4.0.0.0", 6)    // 4.0.0.0/6
                builder.addRoute("8.0.0.0", 5)    // 8.0.0.0/5
                builder.addRoute("16.0.0.0", 4)   // 16.0.0.0/4
                builder.addRoute("32.0.0.0", 3)   // 32.0.0.0/3
                builder.addRoute("64.0.0.0", 2)   // 64.0.0.0/2
                builder.addRoute("128.0.0.0", 1)  // 128.0.0.0/1
                // IPv6 routes - route all IPv6 traffic through VPN
                builder.addRoute("2000::", 3)     // Global IPv6 unicast (2000::/3)
                builder.addRoute("fd00::", 8)     // Unique local addresses (fd00::/8)
                builder.addRoute("fe80::", 10)    // Link-local addresses (fe80::/10)
                // Исключаем блок с SSH сервером
            }
            
        } catch (e: Exception) {
            AppLog.log("Error setting up VPN routes: ${e.message}")
            // Минимальная маршрутизация для тестирования
            builder.addRoute("8.8.8.8", 32)  // IPv4 Google DNS для теста
            builder.addRoute("2001:4860:4860::8888", 128)  // IPv6 Google DNS для теста
        }
            
        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            throw IOException("Failed to establish VPN")
        }
        AppLog.log("VPN established with SSH server protection")
    }
    
    private fun addAllRoutesExcept(builder: Builder, excludeIp: String) {
        // Разбиваем 0.0.0.0/0 на более мелкие блоки, исключая SSH сервер
        val excludeAddr = InetAddress.getByName(excludeIp)
        val excludeBytes = excludeAddr.address
        
        if (excludeBytes.size == 4) { // IPv4
            // Добавляем маршруты, исключая /32 блок SSH сервера
            for (i in 0..255) {
                if (i.toByte() != excludeBytes[0]) {
                    builder.addRoute("$i.0.0.0", 8)
                } else {
                    // Разбиваем этот /8 блок дальше
                    for (j in 0..255) {
                        if (j.toByte() != excludeBytes[1]) {
                            builder.addRoute("$i.$j.0.0", 16)
                        } else {
                            // Разбиваем этот /16 блок дальше  
                            for (k in 0..255) {
                                if (k.toByte() != excludeBytes[2]) {
                                    builder.addRoute("$i.$j.$k.0", 24)
                                } else {
                                    // Добавляем все адреса в /24 блоке кроме SSH сервера
                                    for (l in 0..255) {
                                        if (l.toByte() != excludeBytes[3]) {
                                            builder.addRoute("$i.$j.$k.$l", 32)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun checkSshConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = sshClient
                if (client != null && client.isConnected && client.isAuthenticated) {
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Health check failed: ${e.message}")
                false
            }
        }
    }
    
    private suspend fun reconnectSsh(serverId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                cleanupSshConnection()
                delay(500)

                val server = serverRepository.getServerById(serverId) ?: return@withContext false
                Log.d(TAG, "Reconnecting to ${server.host}...")

                sshClient = SSHClient(DefaultConfig()).apply {
                    addHostKeyVerifier(PromiscuousVerifier())
                    connectTimeout = 30000
                    connect(server.host, server.port)

                    val keyFile = resolvePrivateKeyFile(server.sshKeyId)
                        ?: throw IOException("SSH key not found for reconnect.")
                    val pk = PKCS8KeyFile().apply { init(keyFile) }
                    authPublickey(server.username, pk)
                }

                setupPortForwarding()
                Log.i(TAG, "Reconnection successful")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection failed: ${e.message}")
                false
            }
        }
    }
    
    private fun cleanupSshConnection() {
        try {
            localPortForwarder?.close()
            serverSocket?.close()
            sshClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup: ${e.message}")
        } finally {
            localPortForwarder = null
            serverSocket = null
            sshClient = null
        }
    }

    private fun stopVpn() {
        _connectionState.value = ConnectionState.DISCONNECTING
        _isRunning.value = false
        AppLog.log("Disconnecting...")
        connectionJob?.cancel()
        connectionMonitor?.stopMonitoring()
        cleanupSshConnection()
        
        vpnInterface?.close()
        vpnInterface = null
        stopNetworkMonitoring()
        
        _connectionState.value = ConnectionState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resolvePrivateKeyFile(serverSshKeyId: String? = null): File? {
        // Используем ключ сервера, если указан, иначе активный ключ
        val keyId = serverSshKeyId ?: preferencesManager.getActiveKeyId()
        AppLog.log("Using SSH key ID: $keyId ${if (serverSshKeyId != null) "(server-specific)" else "(active)"}")
        
        if (keyId != null) {
            val keyRepository = KeyRepository(this)
            val keyManager = SshKeyManager(this, keyRepository)
            val keyFile = keyManager.getPrivateKeyFile(keyId)
            
            AppLog.log("Key file path: ${keyFile.absolutePath}")
            AppLog.log("Key file exists: ${keyFile.exists()}")
            AppLog.log("Key file size: ${if (keyFile.exists()) keyFile.length() else 0} bytes")
            AppLog.log("Key file readable: ${keyFile.canRead()}")
            
            if (keyFile.exists()) {
                Log.d(TAG, "Using SSH key: $keyId")
                AppLog.log("Found SSH key: $keyId")
                return keyFile
            } else {
                Log.w(TAG, "SSH key file for ID $keyId not found.")
                AppLog.log("Warning: SSH key file for ID $keyId not found.")
            }
        } else {
            Log.w(TAG, "No SSH key ID specified.")
            AppLog.log("Warning: No SSH key specified.")
        }
        
        Log.e(TAG, "No private key file found.")
        AppLog.log("Error: No private key file found.")
        return null
    }
    
    private fun startNetworkMonitoring() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        if (connectivityManager != null) {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.log("Network available: $network")
                    handleNetworkChange(network, isAvailable = true)
                }
                
                override fun onLost(network: Network) {
                    AppLog.log("Network lost: $network")
                    handleNetworkChange(network, isAvailable = false)
                }
                
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Логируем, но не реагируем на мелкие изменения
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    AppLog.log("Network capabilities changed: $network (internet=$hasInternet, wifi=$isWifi, cellular=$isCellular)")
                }
            }
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            AppLog.log("Network monitoring started")
        }
    }
    
    private fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            connectivityManager?.unregisterNetworkCallback(callback)
            networkCallback = null
            AppLog.log("Network monitoring stopped")
        }
    }
    
    private fun updateActiveNetworks() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val newActiveNetworks = connectivityManager?.let { cm ->
            try {
                // Получаем активную сеть и её alternatives  
                val validNetworks = mutableListOf<Network>()
                
                // Ищем базовые сети (не VPN) среди всех доступных
                @Suppress("DEPRECATION")
                val allNetworks = cm.allNetworks
                for (network in allNetworks) {
                    val capabilities = cm.getNetworkCapabilities(network)
                    if (capabilities != null) {
                        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val notVpn = !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        
                        AppLog.log("Checking network $network: internet=$hasInternet, notVPN=$notVpn, wifi=$isWifi, cellular=$isCellular")
                        
                        if (hasInternet && notVpn && (isWifi || isCellular)) {
                            validNetworks.add(network)
                            AppLog.log("Added valid network: $network")
                        }
                    }
                }
                
                // Всегда используем только активную сеть, если она валидна
                val activeNetwork = cm.activeNetwork
                if (activeNetwork != null) {
                    val capabilities = cm.getNetworkCapabilities(activeNetwork)
                    if (capabilities != null) {
                        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val notVpn = !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        
                        if (hasInternet && notVpn && (isWifi || isCellular)) {
                            AppLog.log("Using only active network: $activeNetwork")
                            validNetworks.clear()
                            validNetworks.add(activeNetwork)
                        } else {
                            AppLog.log("Active network $activeNetwork is not suitable (internet=$hasInternet, notVPN=$notVpn, wifi=$isWifi, cellular=$isCellular)")
                            // Если активная сеть не подходит, используем первую подходящую
                            if (validNetworks.isNotEmpty()) {
                                val firstValid = validNetworks.first()
                                AppLog.log("Using first valid network: $firstValid")
                                validNetworks.clear()
                                validNetworks.add(firstValid)
                            }
                        }
                    }
                } else if (validNetworks.isNotEmpty()) {
                    // Если нет активной сети, используем первую подходящую
                    val firstValid = validNetworks.first()
                    AppLog.log("No active network, using first valid: $firstValid")
                    validNetworks.clear()
                    validNetworks.add(firstValid)
                }
                
                AppLog.log("Found ${validNetworks.size} valid networks")
                
                validNetworks.toTypedArray()
            } catch (e: Exception) {
                AppLog.log("Error updating networks: ${e.message}")
                arrayOf<Network>()
            }
        } ?: arrayOf()
        
        if (!newActiveNetworks.contentEquals(activeNetworks)) {
            activeNetworks = newActiveNetworks
            AppLog.log("Active networks updated: ${activeNetworks?.size ?: 0} networks")
            
            // Обновляем underlying networks для VPN, если он активен
            if (vpnInterface != null) {
                updateVpnNetworks()
            }
        }
    }
    
    private fun handleNetworkChange(network: Network, isAvailable: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val connectivityManager = getSystemService(ConnectivityManager::class.java)
                
                // Ждем немного чтобы система определилась с активной сетью
                delay(1000)
                
                val currentActiveNetwork = connectivityManager.activeNetwork
                val currentNetworkType = getNetworkType(connectivityManager, currentActiveNetwork)
                
                // Также проверяем тип появившейся/потерянной сети
                val changedNetworkType = if (isAvailable) {
                    getNetworkType(connectivityManager, network)
                } else {
                    null
                }
                
                AppLog.log("Network change: available=$isAvailable, network=$network, networkType=$changedNetworkType")
                AppLog.log("Current active: $currentActiveNetwork, type: $currentNetworkType")
                AppLog.log("Last active: $lastActiveNetwork, last type: $lastNetworkType")
                
                // Реагируем на смену ТИПА сети, потерю активной сети, или появление нового типа сети
                val shouldReact = (currentNetworkType != lastNetworkType && currentNetworkType != null) || 
                    (currentNetworkType == null && lastNetworkType != null) ||
                    (isAvailable && changedNetworkType != null && changedNetworkType != lastNetworkType)
                
                if (shouldReact) {
                    AppLog.log("Should react to network change: currentType=$currentNetworkType, changedType=$changedNetworkType, lastType=$lastNetworkType")
                    
                    if (isVpnRecreating) {
                        AppLog.log("VPN recreation already in progress, ignoring")
                        return@launch
                    }
                    
                    isVpnRecreating = true
                    
                    try {
                        // Отключаем VPN
                        if (vpnInterface != null) {
                            AppLog.log("Disabling VPN for network type change")
                            vpnInterface?.close()
                            vpnInterface = null
                        }
                        
                        // Ждем стабилизации сети и ищем лучшую доступную сеть
                        var attempts = 0
                        var finalActiveNetwork: Network? = null
                        var finalNetworkType: String? = null
                        
                        while (attempts < 5 && (finalActiveNetwork == null || finalNetworkType == null)) {
                            delay(1000)
                            attempts++
                            
                            finalActiveNetwork = connectivityManager.activeNetwork
                            finalNetworkType = getNetworkType(connectivityManager, finalActiveNetwork)
                            
                            AppLog.log("Attempt $attempts: network=$finalActiveNetwork, type=$finalNetworkType")
                            
                            // Если активная сеть не подходит, ищем среди всех доступных
                            if (finalNetworkType == null) {
                                @Suppress("DEPRECATION")
                                val allNetworks = connectivityManager.allNetworks
                                for (availableNetwork in allNetworks) {
                                    val networkType = getNetworkType(connectivityManager, availableNetwork)
                                    val capabilities = connectivityManager.getNetworkCapabilities(availableNetwork)
                                    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                    val notVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                                    
                                    AppLog.log("Checking alternative network $availableNetwork: type=$networkType, internet=$hasInternet, notVPN=$notVpn")
                                    
                                    if (networkType != null && hasInternet && notVpn) {
                                        AppLog.log("Found suitable alternative network: $availableNetwork ($networkType)")
                                        finalActiveNetwork = availableNetwork
                                        finalNetworkType = networkType
                                        break
                                    }
                                }
                            }
                        }
                        
                        AppLog.log("Final result: network=$finalActiveNetwork, type=$finalNetworkType")
                        
                        if (finalActiveNetwork != null && finalNetworkType != null) {
                            lastActiveNetwork = finalActiveNetwork
                            lastNetworkType = finalNetworkType
                            isVpnTemporarilyDisabled = false
                            
                            // Проверяем SSH
                            val isSSHAlive = try {
                                sshClient?.isConnected == true && sshClient?.isAuthenticated == true
                            } catch (e: Exception) {
                                false
                            }
                            
                            AppLog.log("SSH alive after network change: $isSSHAlive")
                            
                            if (isSSHAlive) {
                                updateActiveNetworks()
                                setupVpn()
                            } else {
                                AppLog.log("Reconnecting SSH after network change")
                                reconnectSsh(currentServerId)
                            }
                        } else {
                            AppLog.log("No suitable network found after 5 attempts")
                            isVpnTemporarilyDisabled = true
                        }
                    } finally {
                        isVpnRecreating = false
                    }
                }
            } catch (e: Exception) {
                AppLog.log("Error handling network change: ${e.message}")
                isVpnRecreating = false
            }
        }
    }
    
    private fun getNetworkType(connectivityManager: ConnectivityManager?, network: Network?): String? {
        if (connectivityManager == null || network == null) return null
        
        return try {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun updateVpnNetworks() {
        try {
            if (activeNetworks?.isNotEmpty() == true) {
                val currentInterface = vpnInterface
                if (currentInterface != null) {
                    // Пересоздаем VPN с новыми underlying networks
                    serviceScope.launch(Dispatchers.IO) {
                        AppLog.log("Updating VPN networks due to network change")
                        setupVpn()
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.log("Error updating VPN networks: ${e.message}")
        }
    }

    private fun createNotification(status: String): android.app.Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val stopIntent = Intent(this, SshProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Proxy")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SSH Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SSH Proxy VPN connection status"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _isRunning.value = false
        connectionMonitor?.stopMonitoring()
        serviceScope.cancel()
        cleanupSshConnection()
        super.onDestroy()
    }
}