package com.example.sshproxy.ui.servers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.ItemServerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServersAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onServerDelete: (Server) -> Unit,
    private val keyRepository: KeyRepository,
    private val preferencesManager: PreferencesManager,
    private val serverRepository: ServerRepository
) : ListAdapter<Server, ServersAdapter.ServerViewHolder>(ServerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = getItem(position)
        holder.bind(server, onServerClick, onServerDelete, keyRepository, preferencesManager, serverRepository)
    }

    class ServerViewHolder(private val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            server: Server, 
            onClick: (Server) -> Unit, 
            onDelete: (Server) -> Unit,
            keyRepository: KeyRepository,
            preferencesManager: PreferencesManager,
            serverRepository: ServerRepository
        ) {
            binding.tvServerName.text = server.name
            binding.tvServerDetails.text = "${server.username}@${server.host}:${server.port}"
            
            // Показать отпечаток сервера
            val fingerprint = serverRepository.getServerFingerprint(server)
            if (fingerprint != null) {
                binding.tvServerFingerprint.text = "Host key: ${fingerprint.take(20)}..."
                binding.tvServerFingerprint.visibility = android.view.View.VISIBLE
            } else {
                binding.tvServerFingerprint.text = "Host key: Not connected yet"
                binding.tvServerFingerprint.visibility = android.view.View.VISIBLE
            }
            
            // Показать информацию о SSH ключе
            CoroutineScope(Dispatchers.Main).launch {
                val keyInfo = withContext(Dispatchers.IO) {
                    if (server.sshKeyId != null) {
                        val key = keyRepository.getKeyById(server.sshKeyId)
                        key?.name ?: "Unknown key"
                    } else {
                        val activeKeyId = preferencesManager.getActiveKeyId()
                        if (activeKeyId != null) {
                            val activeKey = keyRepository.getKeyById(activeKeyId)
                            "Active: ${activeKey?.name ?: "Unknown"}"
                        } else {
                            "No key"
                        }
                    }
                }
                binding.tvSshKey.text = keyInfo
            }
            
            binding.root.setOnClickListener { onClick(server) }
            binding.btnDelete.setOnClickListener { onDelete(server) }
        }
    }

    class ServerDiffCallback : DiffUtil.ItemCallback<Server>() {
        override fun areItemsTheSame(oldItem: Server, newItem: Server): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Server, newItem: Server): Boolean = oldItem == newItem
    }
}
