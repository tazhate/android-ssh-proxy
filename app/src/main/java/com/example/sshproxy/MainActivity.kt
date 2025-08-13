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
import com.google.android.material.navigation.NavigationBarView

import com.example.sshproxy.data.PreferencesManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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
}