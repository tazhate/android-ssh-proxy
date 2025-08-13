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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainNewBinding
    private lateinit var setupManager: SetupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    
    fun navigateToKeyImport() {
        loadFragment(KeyImportFragment())
    }
    
    fun navigateToAddServer() {
        loadFragment(AddFirstServerFragment())
    }
    
    fun completeSetup() {
        setupManager.completeSetup()
        // Show bottom navigation and go to main app
        binding.bottomNavigation.visibility = android.view.View.VISIBLE
        setupNavigation()
    }

    fun showSetupFlow() {
        loadFragment(KeySetupFragment())
    }

    fun navigateToHome() {
        setupNavigation()
    }
}