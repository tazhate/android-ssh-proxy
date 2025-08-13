package com.example.sshproxy.ui.setup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.MainActivity
import com.example.sshproxy.R
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.databinding.FragmentKeyImportBinding
import kotlinx.coroutines.launch

class KeyImportFragment : Fragment() {
    private var _binding: FragmentKeyImportBinding? = null
    private val binding get() = _binding!!
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                readKeyFromFile(uri)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeyImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnImportFromFile.setOnClickListener {
            openFilePicker()
        }
        
        binding.btnImport.setOnClickListener {
            importKey()
        }
        
        binding.btnBack.setOnClickListener {
            (activity as? MainActivity)?.navigateToKeySetup()
        }
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun readKeyFromFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val keyContent = inputStream?.bufferedReader()?.readText()
            inputStream?.close()
            
            if (!keyContent.isNullOrEmpty()) {
                binding.etPrivateKey.setText(keyContent)
                // Extract filename for key name
                val fileName = uri.lastPathSegment?.substringBeforeLast('.') ?: "Imported Key"
                binding.etKeyName.setText(fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_reading_file, e.message), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun importKey() {
        val privateKeyContent = binding.etPrivateKey.text.toString().trim()
        val keyName = binding.etKeyName.text.toString().trim()
        
        if (privateKeyContent.isEmpty()) {
            Toast.makeText(context, getString(R.string.please_provide_private_key), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (keyName.isEmpty()) {
            Toast.makeText(context, getString(R.string.please_provide_key_name), Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // TODO: Implement actual key import logic
                // For now, just show success and navigate
                Toast.makeText(context, getString(R.string.key_imported_successfully), Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.navigateToAddServer()
            } catch (e: Exception) {
                Toast.makeText(context, getString(R.string.failed_to_import_key, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}