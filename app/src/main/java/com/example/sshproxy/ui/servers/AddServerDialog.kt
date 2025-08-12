package com.example.sshproxy.ui.servers

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.example.sshproxy.data.Server
import com.example.sshproxy.databinding.DialogAddServerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddServerDialog(
    private val server: Server? = null,
    private val onSave: (Server) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAddServerBinding.inflate(layoutInflater)
        
        // Setup initial values
        server?.let {
            binding.etHost.setText(it.host)
            binding.etName.setText(it.name)
            binding.etUser.setText(it.user)
            binding.etPort.setText(it.port.toString())
        } ?: run {
            binding.etUser.setText("user")
            binding.etPort.setText("22")
        }
        
        // Auto-fill name from host
        binding.etHost.doAfterTextChanged { text ->
            if (binding.etName.text.isNullOrEmpty() && !text.isNullOrEmpty()) {
                binding.etName.setText(text.toString())
            }
        }
        
        // Toggle advanced options
        binding.btnToggleAdvanced.setOnClickListener {
            val isVisible = binding.advancedOptionsLayout.visibility == View.VISIBLE
            binding.advancedOptionsLayout.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleAdvanced.setIconResource(
                if (isVisible) com.example.sshproxy.R.drawable.ic_expand_more 
                else com.example.sshproxy.R.drawable.ic_expand_less
            )
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (server == null) "Add Server" else "Edit Server")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val host = binding.etHost.text.toString()
                val name = binding.etName.text?.toString().takeIf { it?.isNotEmpty() == true } ?: host
                val user = binding.etUser.text?.toString() ?: "user"
                val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 22
                
                if (host.isNotEmpty()) {
                    val newServer = Server(
                        id = server?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        host = host,
                        port = port,
                        user = user
                    )
                    onSave(newServer)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
