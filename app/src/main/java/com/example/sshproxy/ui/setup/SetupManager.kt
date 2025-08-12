package com.example.sshproxy.ui.setup

import android.content.Context
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.MainActivity

class SetupManager(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val keyManager = SshKeyManager(activity)

    fun isFirstLaunch(): Boolean {
        return !prefs.getBoolean("setup_complete", false) || !keyManager.hasKeyPair()
    }

    fun showSetupFlow() {
        val fragment = KeySetupFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(com.example.sshproxy.R.id.fragmentContainer, fragment)
            .commit()
    }

    fun completeSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
        activity.onSetupComplete()
    }
}
