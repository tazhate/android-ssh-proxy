package com.example.sshproxy.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.databinding.FragmentKeysBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KeysFragment : Fragment() {
    private var _binding: FragmentKeysBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var keyRepository: KeyRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var adapter: KeysAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        keyRepository = KeyRepository(requireContext())
        preferencesManager = PreferencesManager(requireContext())
        setupRecyclerView()
        
        binding.fabAddKey.setOnClickListener {
            AddKeyDialog { name ->
                lifecycleScope.launch {
                    keyRepository.generateKeyPair(name)
                    // After generating, find the new key and set it as active
                    keyRepository.getAllKeys().collectLatest { keys ->
                        val newKey = keys.find { it.name == name }
                        if (newKey != null) {
                            preferencesManager.setActiveKeyId(newKey.id)
                            adapter.setActiveKeyId(newKey.id)
                        }
                    }
                    Toast.makeText(context, "Key '$name' generated and set as active", Toast.LENGTH_SHORT).show()
                }
            }.show(parentFragmentManager, "add_key")
        }
        
        observeKeys()
    }

    private fun setupRecyclerView() {
        adapter = KeysAdapter(
            onKeyClick = { key ->
                preferencesManager.setActiveKeyId(key.id)
                adapter.setActiveKeyId(key.id)
            },
            onKeyDelete = { key ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Key")
                    .setMessage("Delete key '${key.name}'? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            keyRepository.deleteKey(key.id)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onKeyCopy = { key ->
                copyKeyToClipboard(key.publicKey)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun observeKeys() {
        lifecycleScope.launch {
            keyRepository.getAllKeys().collectLatest { keys ->
                adapter.submitList(keys)

                var activeKeyId = preferencesManager.getActiveKeyId()
                // If no key is active, but keys exist, make the first one active.
                if (activeKeyId == null && keys.isNotEmpty()) {
                    activeKeyId = keys.first().id
                    preferencesManager.setActiveKeyId(activeKeyId)
                }
                adapter.setActiveKeyId(activeKeyId)
                
                binding.emptyView.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun copyKeyToClipboard(publicKey: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SSH Public Key", publicKey)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
