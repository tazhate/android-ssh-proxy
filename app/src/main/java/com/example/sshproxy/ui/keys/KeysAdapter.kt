package com.example.sshproxy.ui.keys

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sshproxy.data.SshKey
import com.example.sshproxy.databinding.ItemKeyBinding

class KeysAdapter(
    private val onKeyClick: (SshKey) -> Unit,
    private val onKeyDelete: (SshKey) -> Unit,
    private val onKeyCopy: (SshKey) -> Unit
) : ListAdapter<SshKey, KeysAdapter.KeyViewHolder>(KeyDiffCallback()) {

    private var activeKeyId: String? = null

    fun setActiveKeyId(id: String?) {
        activeKeyId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return KeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        val key = getItem(position)
        holder.bind(key, key.id == activeKeyId, onKeyClick, onKeyDelete, onKeyCopy)
    }

    class KeyViewHolder(private val binding: ItemKeyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(key: SshKey, isActive: Boolean, onClick: (SshKey) -> Unit, onDelete: (SshKey) -> Unit, onCopy: (SshKey) -> Unit) {
            binding.tvKeyName.text = key.name
            binding.tvKeyFingerprint.text = key.fingerprint
            binding.radioActive.isChecked = isActive
            
            binding.root.setOnClickListener { onClick(key) }
            binding.btnDelete.setOnClickListener { onDelete(key) }
            binding.btnCopyKey.setOnClickListener { onCopy(key) }
        }
    }

    class KeyDiffCallback : DiffUtil.ItemCallback<SshKey>() {
        override fun areItemsTheSame(oldItem: SshKey, newItem: SshKey): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SshKey, newItem: SshKey): Boolean = oldItem == newItem
    }
}
