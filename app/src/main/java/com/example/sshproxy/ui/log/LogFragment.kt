package com.example.sshproxy.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.sshproxy.AppLog
import com.example.sshproxy.databinding.FragmentLogBinding

class LogFragment : Fragment() {
    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        AppLog.logMessages.observe(viewLifecycleOwner) { logs ->
            binding.tvLog.text = logs.joinToString("\n")
            // Auto-scroll to bottom
            binding.scrollView.post {
                binding.scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
        
        binding.fabCopy.setOnClickListener {
            copyLogToClipboard()
        }
    }

    private fun copyLogToClipboard() {
        val logText = binding.tvLog.text.toString()
        if (logText.isNotBlank()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("App Log", logText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
