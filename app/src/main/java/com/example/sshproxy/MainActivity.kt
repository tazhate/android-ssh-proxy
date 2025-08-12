package com.example.sshproxy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.sshproxy.databinding.ActivityMainNewBinding
import com.example.sshproxy.ui.home.HomeFragment
import com.example.sshproxy.ui.instructions.InstructionsFragment
import com.example.sshproxy.ui.keys.KeysFragment
import com.example.sshproxy.ui.log.LogFragment
import com.example.sshproxy.ui.servers.ServersFragment
import com.example.sshproxy.ui.setup.SetupManager
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
            setupManager.showSetupFlow()
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
                R.id.navigation_instructions -> {
                    loadFragment(InstructionsFragment())
                    true
                }
                R.id.navigation_log -> {
                    loadFragment(LogFragment())
                    true
                }
                else -> false
            }
        })
        
        // Load home fragment by default
        binding.bottomNavigation.selectedItemId = R.id.navigation_home
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    fun onSetupComplete() {
        setupNavigation()
    }
}