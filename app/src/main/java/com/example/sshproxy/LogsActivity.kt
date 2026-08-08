package com.example.sshproxy

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer

class LogsActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        logTextView = findViewById(R.id.logTextView)

        // Observe log updates
        LogManager.logs.observe(this, Observer { newLogs ->
            logTextView.text = newLogs.joinToString("\n")
            // Scroll to bottom
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        })
    }
}
