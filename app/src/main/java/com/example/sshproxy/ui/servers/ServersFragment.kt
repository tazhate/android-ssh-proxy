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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ServersFragment : Fragment() {
    private var _binding: FragmentServersBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ServersViewModel by viewModels {
        ServersViewModelFactory(ServerRepository(requireContext()))
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
        
        observeServers()
    }

    private fun setupRecyclerView() {
        val keyRepository = KeyRepository(requireContext())
        val preferencesManager = PreferencesManager(requireContext())
        
        adapter = ServersAdapter(
            onServerClick = { server: Server ->
                AddServerDialog(server) { updatedServer: Server ->
                    viewModel.insertServer(updatedServer)
                }.show(parentFragmentManager, "edit_server")
            },
            onServerDelete = { server: Server ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Server")
                    .setMessage("Delete ${server.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteServer(server)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            keyRepository = keyRepository,
            preferencesManager = preferencesManager
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
            }
        }
    }
    

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
