package com.example.sshproxy.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.sshproxy.R

/**
 * Dialog shown when a server's host key has changed
 * This is a security-critical dialog that warns users about potential MITM attacks
 */
class HostKeyChangeDialog : DialogFragment() {
    
    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"
        private const val ARG_NEW_FINGERPRINT = "new_fingerprint"
        private const val ARG_STORED_FINGERPRINT = "stored_fingerprint"
        
        fun newInstance(
            hostname: String,
            port: Int,
            newFingerprint: String,
            storedFingerprint: String,
            onResult: (Boolean) -> Unit
        ): HostKeyChangeDialog {
            val dialog = HostKeyChangeDialog()
            dialog.onResult = onResult
            dialog.arguments = Bundle().apply {
                putString(ARG_HOSTNAME, hostname)
                putInt(ARG_PORT, port)
                putString(ARG_NEW_FINGERPRINT, newFingerprint)
                putString(ARG_STORED_FINGERPRINT, storedFingerprint)
            }
            return dialog
        }
    }
    
    private var onResult: ((Boolean) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val hostname = arguments?.getString(ARG_HOSTNAME) ?: ""
        val port = arguments?.getInt(ARG_PORT) ?: 22
        val newFingerprint = arguments?.getString(ARG_NEW_FINGERPRINT) ?: ""
        val storedFingerprint = arguments?.getString(ARG_STORED_FINGERPRINT) ?: ""
        
        val hostDisplay = if (port == 22) hostname else "$hostname:$port"
        
        val message = getString(R.string.host_key_changed_message, hostDisplay) + "\n\n" +
                getString(R.string.stored_fingerprint) + "\n$storedFingerprint\n\n" +
                getString(R.string.received_fingerprint) + "\n$newFingerprint\n\n" +
                getString(R.string.host_key_change_warning)
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.security_warning)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(R.string.accept_and_continue) { _, _ ->
                onResult?.invoke(true)
            }
            .setNegativeButton(R.string.reject_connection) { _, _ ->
                onResult?.invoke(false)
            }
            .setCancelable(false) // Force user to make a decision
            .create()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        onResult = null
    }
}