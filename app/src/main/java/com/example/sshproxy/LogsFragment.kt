package com.example.sshproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {

    private lateinit var logText: TextView
    private lateinit var copyFab: FloatingActionButton
    private lateinit var shareFab: FloatingActionButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_logs, container, false)

        logText = view.findViewById(R.id.logText)
        copyFab = view.findViewById(R.id.copyLogsButton)
        shareFab = view.findViewById(R.id.shareLogsButton)

        copyFab.setOnClickListener { copyLogs() }
        shareFab.setOnClickListener { shareLogs() }
        logText.setOnLongClickListener { copyLogs(); true }

        lifecycleScope.launch {
            while (true) {
                val logs = LogManager.getLogs()
                logText.text = logs.joinToString("\n")
                delay(500)
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        val logs = LogManager.getLogs()
        logText.text = logs.joinToString("\n")
    }

    private fun copyLogs() {
        val text = logText.text.toString()
        if (text.isNotEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
            Toast.makeText(requireContext(), "Logs copied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "No logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogs() {
        val text = logText.text.toString()
        if (text.isNotEmpty()) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share logs via"))
        } else {
            Toast.makeText(requireContext(), "No logs", Toast.LENGTH_SHORT).show()
        }
    }
}