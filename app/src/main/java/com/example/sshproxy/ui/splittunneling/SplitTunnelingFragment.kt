package com.example.sshproxy.ui.splittunneling

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sshproxy.R
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.databinding.FragmentSplitTunnelingBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SplitTunnelingFragment : Fragment() {

    private var _binding: FragmentSplitTunnelingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SplitTunnelingViewModel by viewModels {
        SplitTunnelingViewModel.Factory(
            requireContext().packageManager,
            PreferencesManager(requireContext())
        )
    }

    private lateinit var adapter: AppListAdapter
    private var reconnectSnackbar: Snackbar? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSplitTunnelingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupSystemAppsToggle()
        observeViewModel()
        observeVpnState()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { packageName, selected ->
            viewModel.toggleApp(packageName, selected)
        }
        binding.recyclerApps.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerApps.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener { editable ->
            viewModel.setSearchQuery(editable?.toString() ?: "")
        }
    }

    private fun setupSystemAppsToggle() {
        binding.switchSystemApps.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowSystemApps(isChecked)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                binding.recyclerApps.visibility = if (loading) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredApps.collectLatest { apps ->
                adapter.submitList(apps)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCount.collectLatest { count ->
                binding.tvSelectedCount.text = if (count == 0) {
                    getString(R.string.split_tunneling_no_apps_selected)
                } else {
                    resources.getQuantityString(
                        R.plurals.split_tunneling_apps_selected, count, count
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showSystemApps.collectLatest { show ->
                if (binding.switchSystemApps.isChecked != show) {
                    binding.switchSystemApps.isChecked = show
                }
            }
        }
    }

    private fun observeVpnState() {
        viewLifecycleOwner.lifecycleScope.launch {
            SshProxyService.isRunning.collectLatest { running ->
                if (running) {
                    if (reconnectSnackbar == null) {
                        reconnectSnackbar = Snackbar.make(
                            binding.root,
                            R.string.split_tunneling_reconnect_notice,
                            Snackbar.LENGTH_INDEFINITE
                        ).also { it.show() }
                    }
                } else {
                    reconnectSnackbar?.dismiss()
                    reconnectSnackbar = null
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reconnectSnackbar = null
        _binding = null
    }
}
