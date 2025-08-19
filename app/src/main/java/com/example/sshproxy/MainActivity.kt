package com.example.sshproxy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.sshproxy.databinding.ActivityMainNewBinding
import com.example.sshproxy.ui.home.HomeFragment
import com.example.sshproxy.ui.keys.KeysFragment
import com.example.sshproxy.ui.servers.ServersFragment
import com.example.sshproxy.ui.settings.SettingsFragment
import com.example.sshproxy.ui.setup.*
import com.example.sshproxy.ui.dialogs.HostKeyChangeDialog
import com.example.sshproxy.security.SecurityNotificationManager
import com.google.android.material.navigation.NavigationBarView

import com.example.sshproxy.data.PreferencesManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainNewBinding
    private lateinit var setupManager: SetupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val preferencesManager = PreferencesManager(this)
        val theme = preferencesManager.getTheme()
        
        android.util.Log.d("MainActivity", "onCreate - Current theme: $theme")
        
        // Применяем тему ПЕРЕД вызовом super.onCreate()
        applyTheme(theme)
        
        super.onCreate(savedInstanceState)
        
        applyLanguage(preferencesManager.getLanguage())

        binding = ActivityMainNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupManager = SetupManager(this)
        
        // Handle security alerts from notifications
        handleSecurityIntent()
        
        // Check if first launch
        if (setupManager.isFirstLaunch()) {
            startSetupFlow()
        } else {
            setupNavigation()
        }
    }

    private fun applyTheme(theme: String) {
        android.util.Log.d("MainActivity", "Applying theme: $theme")
        when (theme) {
            "light" -> {
                android.util.Log.d("MainActivity", "Setting MODE_NIGHT_NO")
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            "dark" -> {
                android.util.Log.d("MainActivity", "Setting MODE_NIGHT_YES")
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            else -> {
                android.util.Log.d("MainActivity", "Setting MODE_NIGHT_FOLLOW_SYSTEM")
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    private fun applyLanguage(language: String) {
        val localeList = if (language == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener(NavigationBarView.OnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.navigation_servers -> {
                    loadFragment(ServersFragment())
                    true
                }
                R.id.navigation_keys -> {
                    loadFragment(KeysFragment())
                    true
                }
                R.id.navigation_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        })
        
        // Load home fragment by default
        if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) == null) {
            binding.bottomNavigation.selectedItemId = R.id.navigation_home
        }
    }
    

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun onSetupComplete() {
        setupNavigation()
    }

    private fun startSetupFlow() {
        // Hide bottom navigation during setup
        binding.bottomNavigation.visibility = android.view.View.GONE
        loadFragment(WelcomeFragment())
    }
    
    fun navigateToKeySetup() {
        loadFragment(KeyChoiceFragment())
    }
    
    fun navigateToKeyGeneration() {
        loadFragment(KeyGenerationFragment())
    }
    
    
    fun navigateToAddServer() {
        loadFragment(AddFirstServerFragment())
    }
    
    fun completeSetup() {
        setupManager.completeSetup()
        // Show bottom navigation and go to main app
        binding.bottomNavigation.visibility = android.view.View.VISIBLE
        setupNavigation()
        // Load the home fragment immediately after setup
        loadFragment(HomeFragment())
        // Set the home item as selected in bottom navigation
        binding.bottomNavigation.selectedItemId = R.id.navigation_home
    }

    fun showSetupFlow() {
        loadFragment(KeySetupFragment())
    }

    fun navigateToHome() {
        setupNavigation()
    }

    private fun handleSecurityIntent() {
        val showSecurityAlert = intent.getBooleanExtra("show_security_alert", false)
        if (showSecurityAlert) {
            val hostname = intent.getStringExtra("hostname") ?: return
            val port = intent.getIntExtra("port", 22)
            
            // Clear the security notification since user opened the app
            SecurityNotificationManager(this).clearSecurityNotifications()
            
            // Show the host key change dialog
            // For now, we'll just log it since we need the actual fingerprints
            android.util.Log.d("MainActivity", "Host key change detected for $hostname:$port")
            // TODO: Get actual fingerprints and show HostKeyChangeDialog
        }
    }
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // VPN permission granted, start VPN
            startVpnFromWidget()
        } else {
            // VPN permission denied
            android.util.Log.d("MainActivity", "VPN permission denied")
        }
    }
    
    private fun handleVpnStartIntent() {
        android.util.Log.d("MainActivity", "handleVpnStartIntent: action=${intent?.action}")
        if (intent?.action == "com.example.sshproxy.ACTION_START_VPN_FROM_WIDGET") {
            val serverId = intent.getLongExtra("server_id", -1)
            android.util.Log.d("MainActivity", "handleVpnStartIntent: serverId=$serverId")
            if (serverId != -1L) {
                // Save server ID for later use
                getSharedPreferences("ssh_proxy_prefs", MODE_PRIVATE).edit()
                    .putLong("pending_widget_server_id", serverId)
                    .apply()
                
                // Check if VPN permission is needed
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    // Permission needed
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    // Permission already granted
                    startVpnFromWidget()
                }
            }
        }
    }
    
    private fun startVpnFromWidget() {
        android.util.Log.d("MainActivity", "startVpnFromWidget called")
        val prefs = getSharedPreferences("ssh_proxy_prefs", MODE_PRIVATE)
        val serverId = prefs.getLong("pending_widget_server_id", -1)
        android.util.Log.d("MainActivity", "startVpnFromWidget: serverId=$serverId")
        
        if (serverId != -1L) {
            // Clear pending server ID and set connecting state
            prefs.edit()
                .remove("pending_widget_server_id")
                .putBoolean("vpn_connecting", true)
                .apply()
            
            // Update widget to show connecting state
            val updateIntent = Intent("android.appwidget.action.APPWIDGET_UPDATE")
            updateIntent.setPackage(packageName)
            sendBroadcast(updateIntent)
            
            // Start VPN service
            val serviceIntent = Intent(this, SshProxyService::class.java).apply {
                action = SshProxyService.ACTION_START
                putExtra(SshProxyService.EXTRA_SERVER_ID, serverId)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Close activity to return to previous app
            finish()
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVpnStartIntent()
    }
}