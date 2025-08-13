package com.example.sshproxy.ui.home


import android.animation.ValueAnimator
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.R
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.IpLocationService
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.data.ConnectionState
import com.example.sshproxy.data.getDisplayStatus
import com.example.sshproxy.data.getConnectionDuration
import com.example.sshproxy.data.getPingDisplay
import com.example.sshproxy.data.getQualityColor
import com.example.sshproxy.databinding.FragmentHomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            ServerRepository(requireContext()),
            PreferencesManager(requireContext())
        )
    }
    private lateinit var keyManager: SshKeyManager
    private var blinkAnimator: ValueAnimator? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(context, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        keyManager = SshKeyManager(requireContext(), KeyRepository(requireContext()))
        
        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (viewModel.isRunning.value) {
                stopVpnService()
            } else {
                checkAndConnect()
            }
        }

        binding.tvSelectedServer.setOnClickListener {
            showServerSelector()
        }
        
        binding.btnTest.setOnClickListener {
            testConnection()
        }
        
        binding.btnRefreshIp.setOnClickListener {
            // Force refresh when user explicitly requests it
            IpLocationService.forceRefresh()
            refreshIpInfo()
        }
        
        // Показываем IP карточку и загружаем кэшированную информацию (без сетевых запросов)
        binding.cardExternalIp.visibility = View.VISIBLE
        loadCachedIpInfo()
    }

    private fun observeViewModel() {
        // Observe connection status with ping info
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionStatus.collectLatest { status ->
                if (_binding == null) return@collectLatest
                
                val isConnected = status.state == ConnectionState.CONNECTED
                binding.btnConnect.isSelected = isConnected
                
                // Update connection status text
                binding.tvConnectionStatus.text = status.getDisplayStatus()
                
                // Show/hide connection info card
                binding.cardConnectionInfo.visibility = if (isConnected) View.VISIBLE else View.GONE
                
                // Update ping information
                if (isConnected) {
                    binding.tvPing.text = status.getPingDisplay() ?: "--"
                    binding.tvQuality.text = status.connectionQuality.displayName
                    binding.tvConnectionDuration.text = status.getConnectionDuration() ?: "--"
                    
                    // Update quality indicator color
                    binding.viewQualityIndicator.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), android.R.color.transparent)
                    )
                    binding.viewQualityIndicator.setBackgroundColor(status.getQualityColor())
                    
                    // Update ping text color based on latency
                    val pingColor = when {
                        status.latestPing?.isSuccessful != true -> ContextCompat.getColor(requireContext(), R.color.error)
                        (status.latestPing.latencyMs) > 500 -> ContextCompat.getColor(requireContext(), R.color.warning)
                        (status.latestPing.latencyMs) > 200 -> ContextCompat.getColor(requireContext(), R.color.fair)
                        else -> ContextCompat.getColor(requireContext(), R.color.good)
                    }
                    binding.tvPing.setTextColor(pingColor)
                }
                
                // Manage blinking animation
                if (status.state == ConnectionState.CONNECTING || status.state == ConnectionState.DISCONNECTING || status.state == ConnectionState.RECONNECTING) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
                
                // Update button icon
                if (isConnected) {
                    binding.btnConnect.setIconResource(R.drawable.ic_stop)
                } else {
                    binding.btnConnect.setIconResource(R.drawable.ic_power)
                }
                
                // Update IP info on VPN connection state change (smart caching)
                val sharedPrefs = requireActivity().getSharedPreferences("app_prefs", 0)
                val lastKnownState = sharedPrefs.getString("last_connection_state", "")
                val currentStateString = status.state.toString()
                
                if (lastKnownState != currentStateString) {
                    sharedPrefs.edit().putString("last_connection_state", currentStateString).apply()
                    
                    // Invalidate IP cache when VPN state changes
                    val isVpnConnected = status.state == ConnectionState.CONNECTED
                    IpLocationService.invalidateCacheOnVpnChange(isVpnConnected)
                    
                    when (status.state) {
                        ConnectionState.CONNECTED -> {
                            if (_binding != null) {
                                binding.tvCountryFlag.text = "⏳"
                                binding.tvCountryName.text = getString(R.string.vpn_connecting)
                            }
                            // Wait for VPN to stabilize, then refresh IP
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(5000)
                                refreshIpInfo()
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            if (_binding != null) {
                                binding.tvCountryFlag.text = "⏳"
                                binding.tvCountryName.text = getString(R.string.vpn_disconnecting)
                            }
                            // Shorter delay for disconnect, then refresh IP
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(2000)
                                refreshIpInfo()
                            }
                        }
                        else -> { /* no action needed */ }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedServer.collectLatest { server ->
                // Проверяем что binding еще валидный
                if (_binding != null) {
                    binding.tvSelectedServer.text = server?.name ?: getString(R.string.no_server_selected)
                }
            }
        }
    }

    private fun showServerSelector() {
        val servers = viewModel.servers.value
        if (servers.isEmpty()) {
            Toast.makeText(context, getString(R.string.no_servers_configured), Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { it.name }.toTypedArray()
        val currentIndex = servers.indexOfFirst { it.id == viewModel.selectedServer.value?.id }.takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_server))
            .setSingleChoiceItems(serverNames, currentIndex) { dialog, which ->
                viewModel.selectServer(servers[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun checkAndConnect() {
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(context, getString(R.string.requires_android_10), Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!keyManager.hasKeyPair()) {
                Toast.makeText(context, getString(R.string.no_ssh_key_configured), Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (viewModel.selectedServer.value == null) {
                Toast.makeText(context, getString(R.string.please_select_a_server), Toast.LENGTH_SHORT).show()
                return@launch
            }


            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val server = viewModel.selectedServer.value ?: return
        
        val intent = Intent(context, SshProxyService::class.java).apply {
            action = SshProxyService.ACTION_START
            putExtra(SshProxyService.EXTRA_SERVER_ID, server.id)
        }
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(context, SshProxyService::class.java).apply {
            action = SshProxyService.ACTION_STOP
        }
        requireContext().startService(intent)
    }
    
    private val blinkingHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startBlinking() {
        blinkingHandler.removeCallbacksAndMessages(null)
        if (blinkAnimator?.isRunning == true) return

        // Мигание через изменение цвета между серым и белым для максимального контраста
        val grayColor = ContextCompat.getColor(requireContext(), R.color.vpn_button_disconnected)
        val whiteColor = ContextCompat.getColor(requireContext(), android.R.color.white)
        
        blinkAnimator = ValueAnimator.ofArgb(grayColor, whiteColor).apply {
            duration = 400  // Быстрее для более заметного мигания
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                binding.btnConnect.backgroundTintList = ContextCompat.getColorStateList(requireContext(), android.R.color.transparent)
                binding.btnConnect.setBackgroundColor(color)
            }
            start()
        }
    }
    
    private fun stopBlinking() {
        blinkingHandler.postDelayed({
            // Check if binding is still valid before accessing it
            if (_binding != null) {
                blinkAnimator?.cancel()
                blinkAnimator = null
                binding.btnConnect.alpha = 1f
                
                // Возвращаем нормальный background
                binding.btnConnect.backgroundTintList = null
                binding.btnConnect.setBackgroundResource(R.drawable.vpn_button_background)
            } else {
                // Just cancel the animator if binding is null
                blinkAnimator?.cancel()
                blinkAnimator = null
            }
        }, 1000)
    }
    
    private fun testConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Устанавливаем состояние тестирования
                binding.btnTest.isEnabled = false
                binding.btnTest.text = getString(R.string.testing_connection)
                binding.btnTest.setIconTintResource(android.R.color.darker_gray)
                
                val isConnected = withContext(Dispatchers.IO) {
                    try {
                        val url = URL("https://ifconfig.me")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.requestMethod = "GET"
                        
                        val responseCode = connection.responseCode
                        connection.disconnect()
                        
                        responseCode == 200
                    } catch (e: Exception) {
                        false
                    }
                }
                
                // Обновляем UI на основе результата
                if (isConnected) {
                    binding.btnTest.text = getString(R.string.test_passed)
                    binding.btnTest.setIconTintResource(R.color.green_success)
                    Toast.makeText(context, getString(R.string.internet_connection_working), Toast.LENGTH_SHORT).show()
                } else {
                    binding.btnTest.text = getString(R.string.test_failed)
                    binding.btnTest.setIconTintResource(R.color.red_error)
                    Toast.makeText(context, getString(R.string.no_internet_connection), Toast.LENGTH_SHORT).show()
                }
                
                // Возвращаем исходное состояние через 3 секунды
                kotlinx.coroutines.delay(3000)
                binding.btnTest.text = getString(R.string.test_connection)
                binding.btnTest.setIconTintResource(R.color.icon_default)
                binding.btnTest.isEnabled = true
                
            } catch (e: Exception) {
                // Обработка ошибок
                binding.btnTest.text = getString(R.string.test_failed)
                binding.btnTest.setIconTintResource(R.color.red_error)
                binding.btnTest.isEnabled = true
                Toast.makeText(context, getString(R.string.test_failed_with_error, e.message), Toast.LENGTH_SHORT).show()
                
                kotlinx.coroutines.delay(3000)
                binding.btnTest.text = getString(R.string.test_connection)
                binding.btnTest.setIconTintResource(R.color.icon_default)
            }
        }
    }

    private fun loadCachedIpInfo() {
        // Load cached IP info without triggering network requests
        val cachedLocation = IpLocationService.getCachedIpLocation()
        if (cachedLocation != null && _binding != null) {
            binding.tvExternalIp.text = cachedLocation.ip
            binding.tvCountryName.text = cachedLocation.country
            binding.tvCountryFlag.text = cachedLocation.flag
            binding.btnRefreshIp.isEnabled = true
        } else if (_binding != null) {
            // No cache available, show placeholder
            binding.tvExternalIp.text = "--"
            binding.tvCountryName.text = getString(R.string.unknown_location)
            binding.tvCountryFlag.text = "🌍"
            binding.btnRefreshIp.isEnabled = true
        }
    }
    
    private fun refreshIpInfo(retryCount: Int = 0) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Проверяем что binding еще валидный перед каждым обращением
                if (_binding == null) return@launch
                
                // Показываем состояние загрузки только при первой попытке
                if (retryCount == 0) {
                    binding.tvExternalIp.text = getString(R.string.checking_ip)
                    binding.tvCountryName.text = ""
                    binding.tvCountryFlag.text = "🔄"
                    binding.btnRefreshIp.isEnabled = false
                }
                
                // Пытаемся получить полную информацию
                val ipLocation = IpLocationService.getIpLocation()
                
                // Проверяем binding перед обновлением UI
                if (_binding == null) return@launch
                
                if (ipLocation != null) {
                    binding.tvExternalIp.text = ipLocation.ip
                    binding.tvCountryName.text = ipLocation.country
                    binding.tvCountryFlag.text = ipLocation.flag
                } else {
                    // Fallback: получаем только IP
                    val simpleIp = IpLocationService.getSimpleIp()
                    
                    // Снова проверяем binding
                    if (_binding == null) return@launch
                    
                    if (simpleIp != null) {
                        binding.tvExternalIp.text = simpleIp
                        binding.tvCountryName.text = getString(R.string.unknown_location)
                        binding.tvCountryFlag.text = "🌍"
                    } else if (retryCount < 2) {
                        // Повторяем через 2 секунды максимум 2 раза
                        kotlinx.coroutines.delay(2000)
                        refreshIpInfo(retryCount + 1)
                        return@launch
                    } else {
                        // Полная неудача после повторов
                        binding.tvExternalIp.text = getString(R.string.unable_to_fetch_ip)
                        binding.tvCountryName.text = getString(R.string.check_network_connection)
                        binding.tvCountryFlag.text = "❌"
                    }
                }
            } catch (e: Exception) {
                // Проверяем binding перед обработкой ошибки
                if (_binding == null) return@launch
                
                if (retryCount < 2) {
                    // Повторяем при ошибке
                    kotlinx.coroutines.delay(2000)
                    refreshIpInfo(retryCount + 1)
                    return@launch
                } else {
                    binding.tvExternalIp.text = getString(R.string.network_error)
                    binding.tvCountryName.text = getString(R.string.network_error)
                    binding.tvCountryFlag.text = "❌"
                }
            } finally {
                // Финальная проверка binding
                if (_binding != null) {
                    binding.btnRefreshIp.isEnabled = true
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any pending callbacks and animations before destroying the view
        blinkingHandler.removeCallbacksAndMessages(null)
        blinkAnimator?.cancel()
        blinkAnimator = null
        _binding = null
    }
}
