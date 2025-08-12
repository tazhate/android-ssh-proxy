package com.example.sshproxy.ui.keys

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.example.sshproxy.databinding.DialogAddKeyBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddKeyDialog(
    private val onSave: (String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAddKeyBinding.inflate(layoutInflater)
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Generate New Key")
            .setView(binding.root)
            .setPositiveButton("Generate") { _, _ ->
                val name = binding.etKeyName.text.toString().trim()
                if (name.isNotEmpty()) {
                    onSave(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
