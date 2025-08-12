package com.example.sshproxy.ui.servers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sshproxy.data.Server
import com.example.sshproxy.databinding.ItemServerBinding

class ServersAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onServerDelete: (Server) -> Unit
) : ListAdapter<Server, ServersAdapter.ServerViewHolder>(ServerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = getItem(position)
        holder.bind(server, onServerClick, onServerDelete)
    }

    class ServerViewHolder(private val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(server: Server, onClick: (Server) -> Unit, onDelete: (Server) -> Unit) {
            binding.tvServerName.text = server.name
            binding.tvServerDetails.text = "${server.user}@${server.host}:${server.port}"
            
            binding.root.setOnClickListener { onClick(server) }
            binding.btnDelete.setOnClickListener { onDelete(server) }
        }
    }

    class ServerDiffCallback : DiffUtil.ItemCallback<Server>() {
        override fun areItemsTheSame(oldItem: Server, newItem: Server): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Server, newItem: Server): Boolean = oldItem == newItem
    }
}
