package wtf.anurag.hojo.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import wtf.anurag.hojo.connectivity.EpaperConnectivityManager
import wtf.anurag.hojo.data.model.StorageStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultConnectivityRepository @Inject constructor(
    private val context: Context,
    private val connectivityManager: EpaperConnectivityManager,
    private val fileManagerRepository: FileManagerRepository
) : ConnectivityRepository {
    companion object {
        private const val DEFAULT_DEVICE_BASE_URL = "http://crosspoint.local/"
        private const val PREFS_NAME = "connectivity"
        private const val PREF_MANUAL_ENDPOINT = "manual_endpoint"
    }

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    override val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _storageStatus = MutableStateFlow<StorageStatus?>(null)
    override val storageStatus: StateFlow<StorageStatus?> = _storageStatus.asStateFlow()

    private val _deviceBaseUrl = MutableStateFlow(DEFAULT_DEVICE_BASE_URL)
    override val deviceBaseUrl: StateFlow<String> = _deviceBaseUrl.asStateFlow()

    private val _manualEndpointRequested = MutableStateFlow(false)
    override val manualEndpointRequested: StateFlow<Boolean> =
        _manualEndpointRequested.asStateFlow()

    private val _manualEndpointError = MutableStateFlow<String?>(null)
    override val manualEndpointError: StateFlow<String?> = _manualEndpointError.asStateFlow()

    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Delegate to connectivity manager's discovery state
    override val isDiscovering: StateFlow<Boolean>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.isDiscovering
        } else {
            MutableStateFlow(false)
        }

    override suspend fun checkConnection() {
        _isConnected.value = tryPreferredEndpoints()
    }

    private suspend fun tryPreferredEndpoints(): Boolean {
        return tryEndpoint(DEFAULT_DEVICE_BASE_URL) ||
                (savedManualEndpoint()?.let { tryEndpoint(it) } == true)
    }

    private suspend fun tryEndpoint(baseUrl: String): Boolean {
        return try {
            val normalizedUrl = normalizeEndpoint(baseUrl)
            val status = fileManagerRepository.fetchStatus(normalizedUrl)
            _deviceBaseUrl.value = normalizedUrl
            _storageStatus.value = status
            _manualEndpointRequested.value = false
            _manualEndpointError.value = null
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun savedManualEndpoint(): String? {
        return preferences.getString(PREF_MANUAL_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeEndpoint(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    override suspend fun handleConnect(silent: Boolean) {
        if (_isConnecting.value) return
        _isConnecting.value = true
        try {
            withContext(Dispatchers.IO) {
                if (tryPreferredEndpoints()) {
                    _isConnected.value = true
                    return@withContext
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // First try to connect to already-discovered device (fast path)
                    var success = connectivityManager.connectToDiscoveredDevice()

                    if (!success) {
                        // If no discovered device, use the full connection flow
                        success = connectivityManager.connectToDevice()
                    }

                    if (success) {
                        // If we're connected via e-paper's hotspot, bind to it
                        if (connectivityManager.connectionMode.value ==
                            wtf.anurag.hojo.connectivity.EpaperConnectivityManager.ConnectionMode.HOTSPOT) {
                            connectivityManager.bindToEpaperNetwork()
                        }

                        // Update base URL with discovered IP or use default
                        _deviceBaseUrl.value = connectivityManager.getDeviceBaseUrl()

                        // Verify connection and update status
                        _isConnected.value = true
                        updateDeviceStatus()
                    }
                }

                if (!_isConnected.value && !silent) {
                    _manualEndpointRequested.value = true
                    _manualEndpointError.value = null
                }
            }
        } catch (e: Exception) {
            if (!silent) {
                // Log error
                e.printStackTrace()
                _manualEndpointRequested.value = true
                _manualEndpointError.value = e.message
            }
        } finally {
            _isConnecting.value = false
        }
    }

    override suspend fun submitManualEndpoint(url: String) {
        val normalizedUrl = normalizeEndpoint(url)
        _isConnecting.value = true
        try {
            withContext(Dispatchers.IO) {
                if (tryEndpoint(normalizedUrl)) {
                    preferences.edit().putString(PREF_MANUAL_ENDPOINT, normalizedUrl).apply()
                    _isConnected.value = true
                } else {
                    _isConnected.value = false
                    _manualEndpointRequested.value = true
                    _manualEndpointError.value = "Cannot connect to $normalizedUrl"
                }
            }
        } finally {
            _isConnecting.value = false
        }
    }

    override fun dismissManualEndpointPrompt() {
        _manualEndpointRequested.value = false
    }

    override suspend fun updateDeviceStatus() {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!connectivityManager.bindToEpaperNetwork()) {
                        // Try to bind again?
                    }
                }
                val status = fileManagerRepository.fetchStatus(_deviceBaseUrl.value)
                _storageStatus.value = status
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun disconnect() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             connectivityManager.disconnectEpaperHotspot()
         }
         _isConnected.value = false
    }

    override suspend fun unbindNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.unbindNetwork()
        }
    }

    override suspend fun bindToEpaperNetwork(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.bindToEpaperNetwork()
        } else {
            false
        }
    }

    override suspend fun prepareNetworkForApiRequest(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.prepareNetworkForApiRequest()
        } else {
            true // Assume internet is available on older devices
        }
    }
}
