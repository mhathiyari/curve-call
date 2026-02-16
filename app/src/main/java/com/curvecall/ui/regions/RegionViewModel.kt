package com.curvecall.ui.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.curvecall.data.regions.DownloadProgress
import com.curvecall.data.regions.Region
import com.curvecall.data.regions.RegionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionViewModel @Inject constructor(
    private val regionRepository: RegionRepository
) : ViewModel() {

    data class RegionUiState(
        val isLoading: Boolean = true,
        val availableRegions: List<Region> = emptyList(),
        val downloadedRegionIds: Set<String> = emptySet(),
        val downloadProgress: DownloadProgress = DownloadProgress.Idle,
        val errorMessage: String? = null,
        val storageUsedMb: Long = 0
    )

    private val _uiState = MutableStateFlow(RegionUiState())
    val uiState: StateFlow<RegionUiState> = _uiState.asStateFlow()

    init {
        loadRegions()
    }

    fun loadRegions() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            try {
                val available = regionRepository.fetchAvailableRegions()
                val downloaded = regionRepository.getDownloadedRegions()
                val storageBytes = regionRepository.getStorageUsed()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    availableRegions = available,
                    downloadedRegionIds = downloaded.map { it.id }.toSet(),
                    storageUsedMb = storageBytes / (1024 * 1024)
                )
            } catch (e: Exception) {
                val downloaded = regionRepository.getDownloadedRegions()
                val storageBytes = regionRepository.getStorageUsed()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    downloadedRegionIds = downloaded.map { it.id }.toSet(),
                    storageUsedMb = storageBytes / (1024 * 1024),
                    errorMessage = "Could not load regions: ${e.message}"
                )
            }
        }
    }

    fun downloadRegion(regionId: String) {
        val currentProgress = _uiState.value.downloadProgress
        if (currentProgress is DownloadProgress.Downloading ||
            currentProgress is DownloadProgress.Extracting
        ) {
            return
        }

        val region = _uiState.value.availableRegions.find { it.id == regionId } ?: return

        viewModelScope.launch {
            regionRepository.downloadRegion(region).collect { progress ->
                _uiState.value = _uiState.value.copy(downloadProgress = progress)

                if (progress is DownloadProgress.Completed) {
                    refreshDownloadedState()
                }
            }
        }
    }

    fun deleteRegion(regionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            regionRepository.deleteRegion(regionId)
            refreshDownloadedState()

            val current = _uiState.value.downloadProgress
            if (current is DownloadProgress.Completed && current.regionId == regionId ||
                current is DownloadProgress.Failed && current.regionId == regionId
            ) {
                _uiState.value = _uiState.value.copy(downloadProgress = DownloadProgress.Idle)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun refreshDownloadedState() {
        val downloaded = regionRepository.getDownloadedRegions()
        val storageBytes = regionRepository.getStorageUsed()

        _uiState.value = _uiState.value.copy(
            downloadedRegionIds = downloaded.map { it.id }.toSet(),
            storageUsedMb = storageBytes / (1024 * 1024)
        )
    }
}
