package com.example.sshproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {

    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)

        logText = view.findViewById(R.id.logText)
        scrollView = view.findViewById(R.id.scrollView)
        copyButton = view.findViewById(R.id.copyLogsButton)
        shareButton = view.findViewById(R.id.shareLogsButton)

        // Copy all logs
        copyButton.setOnClickListener {
            copyLogs()
        }

        // Share logs
        shareButton.setOnClickListener {
            shareLogs()
        }

        // Long-click to copy all logs
        logText.setOnLongClickListener {
            copyLogs()
            true
        }

        // Live updates from LogManager
        lifecycleScope.launch {
            while (true) {
                updateLogs()
                delay(500) // Refresh every 500ms
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        updateLogs()
    }

    private fun updateLogs() {
        val logs = LogManager.getLogs()
        val text = logs.joinToString("\n")
        logText.text = text

        // Auto-scroll to bottom
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyLogs() {
        val logs = logText.text.toString()
        if (logs.isNotEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogs() {
        val logs = logText.text.toString()
        if (logs.isNotEmpty()) {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, logs)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share logs via"))
        } else {
            Toast.makeText(requireContext(), "No logs to share", Toast.LENGTH_SHORT).show()
        }
    }
}