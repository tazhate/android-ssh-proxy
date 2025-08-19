package com.example.sshproxy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.sshproxy.R

import android.os.Build
import com.example.sshproxy.SshProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask

class VPNStatusWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE_VPN = "com.example.sshproxy.widget.ACTION_TOGGLE_VPN"
        private var lastVpnConnected = false
        private var blinkTimer: Timer? = null
        private var isBlinkOn = true
        
        private fun isVpnServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (SshProxyService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_vpn_status)

            val prefs = context.getSharedPreferences("ssh_proxy_prefs", Context.MODE_PRIVATE)
            
            // Проверяем реальное состояние VPN Service
            val isServiceRunning = isVpnServiceRunning(context)
            
            // Если сервис не запущен, сбрасываем флаги
            if (!isServiceRunning) {
                prefs.edit()
                    .putBoolean("vpn_running", false)
                    .putBoolean("vpn_connecting", false)
                    .apply()
            }
            
            val isVpnRunning = prefs.getBoolean("vpn_running", false) && isServiceRunning
            val isConnecting = prefs.getBoolean("vpn_connecting", false)
            lastVpnConnected = isVpnRunning
            
            android.util.Log.d("VPNStatusWidget", "updateAppWidget: isVpnRunning=$isVpnRunning, isConnecting=$isConnecting, isBlinkOn=$isBlinkOn, serviceRunning=$isServiceRunning")

            val circleColor = when {
                isConnecting -> {
                    // Blinking when connecting
                    if (isBlinkOn) {
                        context.getColor(R.color.widget_connecting)
                    } else {
                        context.getColor(R.color.widget_disconnected)
                    }
                }
                isVpnRunning -> {
                    context.getColor(R.color.widget_connected)
                }
                else -> {
                    context.getColor(R.color.widget_disconnected)
                }
            }
            
            views.setInt(R.id.iv_status_circle, "setColorFilter", circleColor)
            
            // The icon and background remain static
            views.setInt(R.id.iv_ssh_icon, "setImageResource", R.drawable.ic_ssh)
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_background)


            val intent = Intent(context, VPNStatusWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VPN
            }
            val pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.btnToggleVpn, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        
        fun startBlinking(context: Context) {
            stopBlinking()
            blinkTimer = Timer()
            blinkTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    isBlinkOn = !isBlinkOn
                    Handler(Looper.getMainLooper()).post {
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val thisWidget = android.content.ComponentName(context, VPNStatusWidgetProvider::class.java)
                        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                        for (appWidgetId in appWidgetIds) {
                            updateAppWidget(context, appWidgetManager, appWidgetId)
                        }
                    }
                }
            }, 0, 500) // Моргание каждые 500мс
        }
        
        fun stopBlinking(context: Context? = null) {
            android.util.Log.d("VPNStatusWidget", "stopBlinking called")
            blinkTimer?.cancel()
            blinkTimer = null
            isBlinkOn = true
            
            // Если передан контекст, обновляем виджет немедленно
            if (context != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val thisWidget = android.content.ComponentName(context, VPNStatusWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    for (appWidgetId in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, appWidgetId)
                    }
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        android.util.Log.d("VPNStatusWidget", "onReceive: action=${intent.action}")
        if (intent.action == ACTION_TOGGLE_VPN) {
            android.util.Log.d("VPNStatusWidget", "ACTION_TOGGLE_VPN received")
            val prefs = context.getSharedPreferences("ssh_proxy_prefs", Context.MODE_PRIVATE)
            val isVpnRunning = prefs.getBoolean("vpn_running", false)
            val serverId = prefs.getLong("active_server_id", -1)
            if (serverId == -1L && !isVpnRunning) {
                android.widget.Toast.makeText(context, "Please select a server in the app first", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            android.util.Log.d("VPNStatusWidget", "isVpnRunning=$isVpnRunning, serverId=$serverId")
            if (isVpnRunning) {
                android.util.Log.d("VPNStatusWidget", "Stopping VPN")
                // Проверяем что сервис действительно запущен перед остановкой
                if (isVpnServiceRunning(context)) {
                    // Отключение VPN можно делать напрямую
                    val serviceIntent = Intent(context, SshProxyService::class.java)
                    serviceIntent.action = SshProxyService.ACTION_STOP
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VPNStatusWidget", "Error stopping VPN service", e)
                        // Сбрасываем флаги если сервис не запущен
                        prefs.edit()
                            .putBoolean("vpn_running", false)
                            .putBoolean("vpn_connecting", false)
                            .apply()
                        // Обновляем виджет
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val thisWidget = android.content.ComponentName(context, VPNStatusWidgetProvider::class.java)
                        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                        for (appWidgetId in appWidgetIds) {
                            updateAppWidget(context, appWidgetManager, appWidgetId)
                        }
                    }
                } else {
                    android.util.Log.d("VPNStatusWidget", "VPN service not running, resetting flags")
                    // Сбрасываем флаги если сервис не запущен
                    prefs.edit()
                        .putBoolean("vpn_running", false)
                        .putBoolean("vpn_connecting", false)
                        .apply()
                    // Обновляем виджет
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val thisWidget = android.content.ComponentName(context, VPNStatusWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    for (appWidgetId in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, appWidgetId)
                    }
                }
            } else {
                android.util.Log.d("VPNStatusWidget", "Starting VPN via VpnPermissionActivity")
                // Для подключения VPN используем прозрачную activity
                val activityIntent = Intent(context, com.example.sshproxy.VpnPermissionActivity::class.java).apply {
                    putExtra("server_id", serverId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                context.startActivity(activityIntent)
            }
        }
    }
}
