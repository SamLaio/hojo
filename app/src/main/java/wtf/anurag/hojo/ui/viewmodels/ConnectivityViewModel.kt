package wtf.anurag.hojo.ui.viewmodels

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.anurag.hojo.data.ConnectivityRepository
import wtf.anurag.hojo.data.model.StorageStatus
import javax.inject.Inject

@HiltViewModel
@RequiresApi(Build.VERSION_CODES.Q)
class ConnectivityViewModel @Inject constructor(
    private val connectivityRepository: ConnectivityRepository
) : ViewModel() {

    val isConnected: StateFlow<Boolean> = connectivityRepository.isConnected
    val isConnecting: StateFlow<Boolean> = connectivityRepository.isConnecting
    val storageStatus: StateFlow<StorageStatus?> = connectivityRepository.storageStatus
    val deviceBaseUrl: StateFlow<String> = connectivityRepository.deviceBaseUrl
    val isDiscovering: StateFlow<Boolean> = connectivityRepository.isDiscovering
    val manualEndpointRequested: StateFlow<Boolean> =
        connectivityRepository.manualEndpointRequested
    val manualEndpointError: StateFlow<String?> = connectivityRepository.manualEndpointError
    private val _isCheckingConnection = MutableStateFlow(false)
    val isCheckingConnection: StateFlow<Boolean> = _isCheckingConnection.asStateFlow()

    init {
        checkConnection()
        // Only try to connect once on init, do not loop aggressively
        handleConnect(silent = true)
    }

    fun checkConnection(showLoading: Boolean = false) {
        viewModelScope.launch {
            if (showLoading) _isCheckingConnection.value = true
            try {
                connectivityRepository.checkConnection()
            } finally {
                if (showLoading) _isCheckingConnection.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleConnect(silent: Boolean = false) {
        viewModelScope.launch {
            connectivityRepository.handleConnect(silent)
        }
    }

    fun submitManualEndpoint(url: String) {
        viewModelScope.launch {
            connectivityRepository.submitManualEndpoint(url)
        }
    }

    fun dismissManualEndpointPrompt() {
        connectivityRepository.dismissManualEndpointPrompt()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun updateDeviceStatus() {
        viewModelScope.launch {
            connectivityRepository.updateDeviceStatus()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectivityRepository.disconnect()
        }
    }
}
