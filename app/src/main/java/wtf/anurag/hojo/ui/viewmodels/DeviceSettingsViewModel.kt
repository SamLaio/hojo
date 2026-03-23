package wtf.anurag.hojo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.anurag.hojo.data.ConnectivityRepository
import wtf.anurag.hojo.data.DeviceSettingsRepository
import wtf.anurag.hojo.data.model.DeviceSetting

@HiltViewModel
class DeviceSettingsViewModel
@Inject
constructor(
        private val settingsRepository: DeviceSettingsRepository,
        private val connectivityRepository: ConnectivityRepository
) : ViewModel() {

    private val _settings = MutableStateFlow<List<DeviceSetting>>(emptyList())
    val settings: StateFlow<List<DeviceSetting>> = _settings.asStateFlow()

    // Staged changes not yet sent to device: key → new value
    private val _pendingChanges = MutableStateFlow<Map<String, Any>>(emptyMap())
    val pendingChanges: StateFlow<Map<String, Any>> = _pendingChanges.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _pendingChanges.value = emptyMap()
            try {
                val baseUrl = connectivityRepository.deviceBaseUrl.value
                _settings.value = settingsRepository.fetchSettings(baseUrl)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load settings: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Stage a change locally without sending it to the device yet. */
    fun stageSetting(key: String, value: Any) {
        _pendingChanges.value = _pendingChanges.value + (key to value)
    }

    /** Send all staged changes to the device. */
    fun saveSettings() {
        val changes = _pendingChanges.value
        if (changes.isEmpty()) return
        val baseUrl = connectivityRepository.deviceBaseUrl.value
        viewModelScope.launch {
            _isSaving.value = true
            _errorMessage.value = null
            try {
                settingsRepository.updateSettings(baseUrl, changes)
                // Merge pending into server state and clear pending
                _settings.value = _settings.value.map { setting ->
                    val pending = changes[setting.key] ?: return@map setting
                    when (setting) {
                        is DeviceSetting.Toggle -> setting.copy(value = pending as Boolean)
                        is DeviceSetting.Enum -> setting.copy(value = pending as Int)
                        is DeviceSetting.Value -> setting.copy(value = pending as Int)
                        is DeviceSetting.Text -> setting.copy(value = pending as String)
                    }
                }
                _pendingChanges.value = emptyMap()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save settings: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
