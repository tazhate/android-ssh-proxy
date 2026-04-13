package com.example.sshproxy.ui.splittunneling

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sshproxy.databinding.ItemAppBinding

class AppListAdapter(
    private val onToggle: (packageName: String, selected: Boolean) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    private val iconCache = HashMap<String, Drawable>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position), iconCache, onToggle)
    }

    class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            app: AppInfo,
            iconCache: HashMap<String, Drawable>,
            onToggle: (String, Boolean) -> Unit
        ) {
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName

            val icon = iconCache.getOrPut(app.packageName) {
                try {
                    binding.root.context.packageManager.getApplicationIcon(app.packageName)
                } catch (e: Exception) {
                    binding.root.context.packageManager.defaultActivityIcon
                }
            }
            binding.imgAppIcon.setImageDrawable(icon)

            // Remove listener before setting checked state to avoid callback loops
            binding.checkboxSelected.setOnCheckedChangeListener(null)
            binding.checkboxSelected.isChecked = app.isSelected
            binding.checkboxSelected.setOnCheckedChangeListener { _, isChecked ->
                onToggle(app.packageName, isChecked)
            }

            binding.root.setOnClickListener {
                binding.checkboxSelected.toggle()
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.isSelected == newItem.isSelected && oldItem.appName == newItem.appName
    }
}
