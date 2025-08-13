package com.example.sshproxy.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.VpnService
import android.os.Build
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
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
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
            refreshIpInfo()
        }
        
        // Показываем IP карточку и загружаем информацию
        binding.cardExternalIp.visibility = View.VISIBLE
        refreshIpInfo()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            SshProxyService.connectionState.collectLatest { state ->
                val isRunning = state == SshProxyService.ConnectionState.CONNECTED
                binding.btnConnect.isSelected = isRunning
                
                binding.tvConnectionStatus.text = when (state) {
                    SshProxyService.ConnectionState.DISCONNECTED -> "Disconnected"
                    SshProxyService.ConnectionState.CONNECTING -> "Connecting..."
                    SshProxyService.ConnectionState.CONNECTED -> "Connected"
                    SshProxyService.ConnectionState.DISCONNECTING -> "Disconnecting..."
                }
                
                // Управляем анимацией мигания
                if (state == SshProxyService.ConnectionState.CONNECTING || state == SshProxyService.ConnectionState.DISCONNECTING) {
                    startBlinking()
                } else {
                    stopBlinking()
                }
                
                if (isRunning) {
                    binding.btnConnect.setIconResource(R.drawable.ic_stop)
                } else {
                    binding.btnConnect.setIconResource(R.drawable.ic_power)
                }
                
                // Обновляем IP информацию при изменении состояния соединения
                if (state == SshProxyService.ConnectionState.CONNECTED) {
                    // Показываем что IP будет обновлен
                    binding.tvCountryFlag.text = "⏳"
                    binding.tvCountryName.text = "VPN connecting..."
                    
                    // Большая задержка для подключения чтобы VPN точно заработал
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(5000)
                        refreshIpInfo()
                    }
                } else if (state == SshProxyService.ConnectionState.DISCONNECTED) {
                    // Показываем что IP будет обновлен
                    binding.tvCountryFlag.text = "⏳"
                    binding.tvCountryName.text = "VPN disconnecting..."
                    
                    // Меньшая задержка для отключения
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(3000) 
                        refreshIpInfo()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedServer.collectLatest { server ->
                binding.tvSelectedServer.text = server?.name ?: "No server selected"
            }
        }
    }

    private fun showServerSelector() {
        val servers = viewModel.servers.value
        if (servers.isEmpty()) {
            Toast.makeText(context, "No servers configured", Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { it.name }.toTypedArray()
        val currentIndex = servers.indexOfFirst { it.id == viewModel.selectedServer.value?.id }.takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Server")
            .setSingleChoiceItems(serverNames, currentIndex) { dialog, which ->
                viewModel.selectServer(servers[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun checkAndConnect() {
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(context, "Requires Android 10+", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!keyManager.hasKeyPair()) {
                Toast.makeText(context, "No SSH key configured", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (viewModel.selectedServer.value == null) {
                Toast.makeText(context, "Please select a server", Toast.LENGTH_SHORT).show()
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
    
    private fun startBlinking() {
        stopBlinking() // Останавливаем предыдущую анимацию
        
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
        blinkAnimator?.cancel()
        blinkAnimator = null
        binding.btnConnect.alpha = 1f
        
        // Возвращаем нормальный background
        binding.btnConnect.backgroundTintList = null
        binding.btnConnect.setBackgroundResource(R.drawable.vpn_button_background)
    }
    
    private fun testConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Устанавливаем состояние тестирования
                binding.btnTest.isEnabled = false
                binding.btnTest.text = "Testing..."
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
                    binding.btnTest.text = "Test Passed"
                    binding.btnTest.setIconTintResource(R.color.green_success)
                    Toast.makeText(context, "Internet connection is working", Toast.LENGTH_SHORT).show()
                } else {
                    binding.btnTest.text = "Test Failed"
                    binding.btnTest.setIconTintResource(R.color.red_error)
                    Toast.makeText(context, "No internet connection", Toast.LENGTH_SHORT).show()
                }
                
                // Возвращаем исходное состояние через 3 секунды
                kotlinx.coroutines.delay(3000)
                binding.btnTest.text = "Test Connection"
                binding.btnTest.setIconTintResource(R.color.icon_default)
                binding.btnTest.isEnabled = true
                
            } catch (e: Exception) {
                // Обработка ошибок
                binding.btnTest.text = "Test Failed"
                binding.btnTest.setIconTintResource(R.color.red_error)
                binding.btnTest.isEnabled = true
                Toast.makeText(context, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                
                kotlinx.coroutines.delay(3000)
                binding.btnTest.text = "Test Connection"
                binding.btnTest.setIconTintResource(R.color.icon_default)
            }
        }
    }

    private fun refreshIpInfo(retryCount: Int = 0) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Показываем состояние загрузки только при первой попытке
                if (retryCount == 0) {
                    binding.tvExternalIp.text = "Checking..."
                    binding.tvCountryName.text = ""
                    binding.tvCountryFlag.text = "🔄"
                    binding.btnRefreshIp.isEnabled = false
                }
                
                // Пытаемся получить полную информацию
                val ipLocation = IpLocationService.getIpLocation()
                if (ipLocation != null) {
                    binding.tvExternalIp.text = ipLocation.ip
                    binding.tvCountryName.text = ipLocation.country
                    binding.tvCountryFlag.text = ipLocation.flag
                } else {
                    // Fallback: получаем только IP
                    val simpleIp = IpLocationService.getSimpleIp()
                    if (simpleIp != null) {
                        binding.tvExternalIp.text = simpleIp
                        binding.tvCountryName.text = "Unknown location"
                        binding.tvCountryFlag.text = "🌍"
                    } else if (retryCount < 2) {
                        // Повторяем через 2 секунды максимум 2 раза
                        kotlinx.coroutines.delay(2000)
                        refreshIpInfo(retryCount + 1)
                        return@launch
                    } else {
                        // Полная неудача после повторов
                        binding.tvExternalIp.text = "Unable to fetch"
                        binding.tvCountryName.text = "Check network connection"
                        binding.tvCountryFlag.text = "❌"
                    }
                }
            } catch (e: Exception) {
                if (retryCount < 2) {
                    // Повторяем при ошибке
                    kotlinx.coroutines.delay(2000)
                    refreshIpInfo(retryCount + 1)
                    return@launch
                } else {
                    binding.tvExternalIp.text = "Error"
                    binding.tvCountryName.text = "Network error"
                    binding.tvCountryFlag.text = "❌"
                }
            } finally {
                binding.btnRefreshIp.isEnabled = true
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopBlinking()
        _binding = null
    }
}
