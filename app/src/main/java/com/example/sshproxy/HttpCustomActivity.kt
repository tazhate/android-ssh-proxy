package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class HttpCustomActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            val color = when (status) {
                "Connected" -> android.R.color.holo_green_dark
                "Disconnected" -> android.R.color.holo_red_dark
                "Connecting..." -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_red_dark
            }
            val configFragment = supportFragmentManager.findFragmentByTag("f0") as? ConfigFragment
            configFragment?.updateStatus(status, color)
            configFragment?.setDisconnectEnabled(status == "Connected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_http_custom)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Config"
                else -> "Logs"
            }
        }.attach()

        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, IntentFilter("VPN_STATUS"))
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}
