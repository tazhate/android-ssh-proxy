package com.example.sshproxy.ui.keys

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.example.sshproxy.R
import com.example.sshproxy.databinding.DialogAddKeyBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddKeyDialog(
    private val onSave: (String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAddKeyBinding.inflate(layoutInflater)
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(com.example.sshproxy.R.string.generate_new_key))
            .setView(binding.root)
            .setPositiveButton(getString(com.example.sshproxy.R.string.generate)) { _, _ ->
                val name = binding.etKeyName.text.toString().trim()
                if (name.isNotEmpty()) {
                    onSave(name)
                }
            }
            .setNegativeButton(getString(com.example.sshproxy.R.string.cancel), null)
            .create()
    }
}
