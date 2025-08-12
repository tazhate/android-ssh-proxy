package com.example.sshproxy.ui.servers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.FragmentServersBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ServersFragment : Fragment() {
    private var _binding: FragmentServersBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var serverRepository: ServerRepository
    private lateinit var adapter: ServersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        serverRepository = ServerRepository(requireContext())
        setupRecyclerView()
        
        binding.fabAddServer.setOnClickListener {
            AddServerDialog { server ->
                serverRepository.addServer(server)
                loadServers()
            }.show(parentFragmentManager, "add_server")
        }
        
        loadServers()
    }

    private fun setupRecyclerView() {
        adapter = ServersAdapter(
            onServerClick = { server: Server ->
                AddServerDialog(server) { updatedServer: Server ->
                    serverRepository.updateServer(updatedServer)
                    loadServers()
                }.show(parentFragmentManager, "edit_server")
            },
            onServerDelete = { server: Server ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Server")
                    .setMessage("Delete ${server.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        serverRepository.removeServer(server.id)
                        loadServers()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun loadServers() {
        val servers = serverRepository.getServers()
        adapter.submitList(servers)
        
        binding.emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
