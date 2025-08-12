package com.example.sshproxy.ui.keys

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.databinding.FragmentKeysBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
                keyRepository.generateKeyPair(name)
                loadKeys()
                Toast.makeText(context, "Key '$name' generated", Toast.LENGTH_SHORT).show()
            }.show(parentFragmentManager, "add_key")
        }
        
        loadKeys()
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
                        keyRepository.deleteKey(key.id)
                        loadKeys()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun loadKeys() {
        val keys = keyRepository.getKeys()
        adapter.submitList(keys)
        adapter.setActiveKeyId(preferencesManager.getActiveKeyId())
        
        binding.emptyView.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
