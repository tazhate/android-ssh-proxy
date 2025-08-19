package com.example.sshproxy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle

class VpnPermissionActivity : Activity() {
    
    companion object {
        private const val VPN_REQUEST_CODE = 1
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // No UI, just handle VPN permission
        handleVpnStartIntent()
    }
    
    private fun handleVpnStartIntent() {
        val serverId = intent.getLongExtra("server_id", -1)
        android.util.Log.d("VpnPermissionActivity", "handleVpnStartIntent: serverId=$serverId")
        
        if (serverId != -1L) {
            // Save server ID for later use
            getSharedPreferences("ssh_proxy_prefs", MODE_PRIVATE).edit()
                .putLong("pending_widget_server_id", serverId)
                .apply()
            
            // Check if VPN permission is needed
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // Permission needed
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
            } else {
                // Permission already granted
                startVpnFromWidget()
                finish()
            }
        } else {
            finish()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // VPN permission granted, start VPN
                startVpnFromWidget()
            } else {
                // VPN permission denied
                android.util.Log.d("VpnPermissionActivity", "VPN permission denied")
            }
            finish()
        }
    }
    
    private fun startVpnFromWidget() {
        android.util.Log.d("VpnPermissionActivity", "startVpnFromWidget called")
        val prefs = getSharedPreferences("ssh_proxy_prefs", MODE_PRIVATE)
        val serverId = prefs.getLong("pending_widget_server_id", -1)
        android.util.Log.d("VpnPermissionActivity", "startVpnFromWidget: serverId=$serverId")
        
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
        }
    }
}