package com.example.sshproxy.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.sshproxy.MainActivity
import com.example.sshproxy.R

/**
 * Manages security-related notifications for host key changes and other critical events
 */
class SecurityNotificationManager(private val context: Context) {
    
    companion object {
        private const val SECURITY_CHANNEL_ID = "security_alerts"
        private const val HOST_KEY_CHANGE_ID = 1001
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createSecurityChannel()
    }
    
    /**
     * Show notification about host key change that requires user attention
     */
    fun showHostKeyChangeNotification(hostname: String, port: Int) {
        val hostDisplay = if (port == 22) hostname else "$hostname:$port"
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_security_alert", true)
            putExtra("hostname", hostname)
            putExtra("port", port)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, SECURITY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.security_warning))
            .setContentText(context.getString(R.string.host_key_changed_notification, hostDisplay))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.host_key_changed_notification_detail, hostDisplay)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(HOST_KEY_CHANGE_ID, notification)
    }
    
    /**
     * Clear security notifications
     */
    fun clearSecurityNotifications() {
        notificationManager.cancel(HOST_KEY_CHANGE_ID)
    }
    
    private fun createSecurityChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SECURITY_CHANNEL_ID,
                context.getString(R.string.security_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.security_alerts_channel_description)
                enableLights(true)
                enableVibration(true)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }
}