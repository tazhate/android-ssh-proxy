package com.example.sshproxy.ui.servers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.databinding.FragmentServersBinding
import com.example.sshproxy.network.ConnectionQuality
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class ServersFragment : Fragment() {
    private var _binding: FragmentServersBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ServersViewModel by viewModels {
        ServersViewModelFactory(
            ServerRepository(requireContext()),
            PreferencesManager(requireContext())
        )
    }
    private lateinit var adapter: ServersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        
        binding.fabAddServer.setOnClickListener {
            AddServerDialog { server ->
                viewModel.insertServer(server)
            }.show(parentFragmentManager, "add_server")
        }
        
        binding.btnTestAllServers.setOnClickListener {
            testAllServers()
        }
        
        observeServers()
    }

    private fun setupRecyclerView() {
        val keyRepository = KeyRepository(requireContext())
        val preferencesManager = PreferencesManager(requireContext())
        val serverRepository = ServerRepository(requireContext())
        
        adapter = ServersAdapter(
            onServerClick = { server: Server ->
                AddServerDialog(server) { updatedServer: Server ->
                    viewModel.insertServer(updatedServer)
                }.show(parentFragmentManager, "edit_server")
            },
            onServerDelete = { server: Server ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(com.example.sshproxy.R.string.delete_server))
                    .setMessage(getString(com.example.sshproxy.R.string.delete_server_confirmation_with_name, server.name))
                    .setPositiveButton(getString(com.example.sshproxy.R.string.delete)) { _, _ ->
                        viewModel.deleteServer(server)
                    }
                    .setNegativeButton(getString(com.example.sshproxy.R.string.cancel), null)
                    .show()
            },
            keyRepository = keyRepository,
            preferencesManager = preferencesManager,
            serverRepository = serverRepository
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun observeServers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.servers.collectLatest { servers ->
                adapter.submitList(servers)
                binding.emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerView.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
                binding.btnTestAllServers.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun testAllServers() {
        val servers = adapter.currentList
        if (servers.isEmpty()) {
            return
        }
        
        // Disable test button during testing
        binding.btnTestAllServers.isEnabled = false
        binding.btnTestAllServers.text = "Testing..."
        
        // Clear previous test results
        adapter.clearTestResults()
        
        val serverTester = ServerTester(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Test each server sequentially
                for (server in servers) {
                    // Show loading state
                    adapter.updateTestResult(ServerTestResult.loading(server.id))
                    
                    // Perform the test
                    val result = serverTester.test(server)
                    adapter.updateTestResult(result)
                    
                    // Small delay between tests to avoid overwhelming the network
                    delay(500)
                }
            } finally {
                // Re-enable test button
                binding.btnTestAllServers.isEnabled = true
                binding.btnTestAllServers.text = getString(com.example.sshproxy.R.string.test_all_servers)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
