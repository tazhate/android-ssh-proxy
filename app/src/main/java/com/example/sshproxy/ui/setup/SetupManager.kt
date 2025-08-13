package com.example.sshproxy.ui.setup

import android.content.Context
import android.content.SharedPreferences
import com.example.sshproxy.MainActivity

class SetupManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("setup_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun showSetupFlow() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        (context as? MainActivity)?.showSetupFlow()
    }

    fun completeSetup() {
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .putBoolean(KEY_FIRST_LAUNCH, false)
            .apply()
        (context as? MainActivity)?.navigateToHome()
    }
    
    fun markFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }
}