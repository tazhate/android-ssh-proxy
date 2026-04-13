package com.example.sshproxy.ui.splittunneling

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sshproxy.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelingViewModel(
    private val packageManager: PackageManager,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _allApps = MutableStateFlow<List<AppInfo>>(emptyList())

    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    private var searchQuery: String = ""

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val savedPackages = preferencesManager.getSplitTunnelingApps().toMutableSet()
                val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                val installedSet = installedApps.map { it.packageName }.toSet()

                // Clean up stale entries (uninstalled apps)
                val stale = savedPackages - installedSet
                if (stale.isNotEmpty()) {
                    savedPackages -= stale
                    preferencesManager.setSplitTunnelingApps(savedPackages)
                }

                installedApps
                    .map { appInfo ->
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName = packageManager.getApplicationLabel(appInfo).toString(),
                            isSystem = isSystem,
                            isSelected = appInfo.packageName in savedPackages
                        )
                    }
                    .sortedWith(compareBy({ !it.isSelected }, { it.appName.lowercase() }))
            }
            _allApps.value = apps
            _selectedCount.value = apps.count { it.isSelected }
            _isLoading.value = false
            applyFilter()
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        applyFilter()
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
        applyFilter()
    }

    private fun applyFilter() {
        val base = _allApps.value
        val showSystem = _showSystemApps.value
        val query = searchQuery.trim().lowercase()

        _filteredApps.value = base
            .filter { if (!showSystem) !it.isSystem else true }
            .filter { if (query.isNotEmpty()) it.appName.lowercase().contains(query) else true }
    }

    fun toggleApp(packageName: String, selected: Boolean) {
        _allApps.value = _allApps.value.map { app ->
            if (app.packageName == packageName) app.copy(isSelected = selected) else app
        }
        _selectedCount.value = _allApps.value.count { it.isSelected }
        applyFilter()

        val newSet = _allApps.value
            .filter { it.isSelected }
            .map { it.packageName }
            .toSet()
        preferencesManager.setSplitTunnelingApps(newSet)
    }

    class Factory(
        private val packageManager: PackageManager,
        private val preferencesManager: PreferencesManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SplitTunnelingViewModel(packageManager, preferencesManager) as T
    }
}
