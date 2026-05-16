package wtf.anurag.hojo.ui.apps.converter

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.anurag.hojo.data.FileManagerRepository
import wtf.anurag.hojo.data.model.TaskStatus
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

@HiltViewModel
class ConverterViewModel @Inject constructor(
    application: Application,
    private val repository: FileManagerRepository,
    private val taskRepository: wtf.anurag.hojo.data.TaskRepository
) : AndroidViewModel(application) {

    private val _status = MutableStateFlow<ConverterStatus>(ConverterStatus.Idle)
    val status: StateFlow<ConverterStatus> = _status.asStateFlow()

    private val _settings = MutableStateFlow(ConverterSettings())
    val settings: StateFlow<ConverterSettings> = _settings.asStateFlow()

    private val _selectedFile = MutableStateFlow<Uri?>(null)
    val selectedFile: StateFlow<Uri?> = _selectedFile.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _availableFonts = MutableStateFlow<List<File>>(emptyList())
    val availableFonts: StateFlow<List<File>> = _availableFonts.asStateFlow()

    private val _uploadState = MutableStateFlow(ConverterUploadState())
    val uploadState: StateFlow<ConverterUploadState> = _uploadState.asStateFlow()

    // WebView-based converter (initialized lazily on first conversion)
    private var webViewConverter: WebViewConverter? = null
    private var converterInitialized = false
    private var uploadObserverJob: Job? = null

    init {
        loadFonts()
    }

    override fun onCleared() {
        super.onCleared()
        webViewConverter?.destroy()
        webViewConverter = null
    }

    private suspend fun getConverter(): WebViewConverter {
        if (converterInitialized) return webViewConverter!!
        val converter = WebViewConverter(getApplication())
        webViewConverter = converter
        converter.initialize()
        converterInitialized = true
        return converter
    }

    private fun getFontsDir(): File {
        val dir = File(getApplication<Application>().filesDir, "imported_fonts")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun loadFonts() {
        viewModelScope.launch(Dispatchers.IO) {
            val fontsDir = getFontsDir()
            if (fontsDir.exists() && fontsDir.isDirectory) {
                val fonts =
                        fontsDir
                                .listFiles { file ->
                                    file.extension.equals("ttf", ignoreCase = true) ||
                                            file.extension.equals("otf", ignoreCase = true) ||
                                            file.extension.equals("ttc", ignoreCase = true)
                                }
                                ?.toList()
                                ?.sortedBy { it.name }
                                ?: emptyList()
                _availableFonts.value = fonts
            }
        }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri)
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val fontsDir = getFontsDir()
                    val destFile = File(fontsDir, fileName)
                    FileOutputStream(destFile).use { output -> inputStream.copyTo(output) }
                    _settings.value = _settings.value.copy(fontFamily = destFile.absolutePath)
                    loadFonts()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun selectFile(uri: Uri, invalidFileMessage: String = "Please select an .epub file") {
        val fileName = getFileName(uri)
        if (!fileName.endsWith(".epub", ignoreCase = true)) {
            _selectedFile.value = null
            _selectedFileName.value = null
            _status.value = ConverterStatus.Error(invalidFileMessage)
            return
        }
        _selectedFile.value = uri
        _selectedFileName.value = fileName
        _status.value = ConverterStatus.Idle
    }

    fun updateSettings(newSettings: ConverterSettings) {
        _settings.value = newSettings
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor =
                getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            if (result != null) {
                val cut = result.lastIndexOf('/')
                if (cut != -1) {
                    result = result.substring(cut + 1)
                }
            }
        }
        return result ?: "book.epub"
    }

    private fun sanitizeOutputBaseName(name: String): String {
        val sanitized =
            name
                .replace(Regex("""[\\/:*?"<>|\r\n]"""), "_")
                .replace(Regex("""\s+"""), "_")
                .trim('_', '.')
        return sanitized.ifBlank { "book" }
    }

    fun startConversion() {
        val uri = _selectedFile.value ?: return
        _status.value = ConverterStatus.ReadingFile

        viewModelScope.launch {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _status.value = ConverterStatus.Error("Failed to open file")
                    return@launch
                }

                _status.value = ConverterStatus.Converting(0, 100)

                val epubBytes = withContext(Dispatchers.IO) { inputStream.use { it.readBytes() } }

                val currentSettings = _settings.value
                val originalName = sanitizeOutputBaseName(getFileName(uri).substringBeforeLast("."))
                val fileName =
                    "$originalName.${currentSettings.bookExtension}"
                val outputFile = File(getApplication<Application>().cacheDir, fileName)

                val converter = getConverter()
                converter.convertEpub(epubBytes, outputFile, currentSettings) { current, total ->
                    _status.value = ConverterStatus.Converting(current, total)
                }

                _status.value = ConverterStatus.Preview(outputFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _status.value = ConverterStatus.Error("Conversion failed: ${e.message}")
            }
        }
    }

    fun reset() {
        _status.value = ConverterStatus.Idle
        _selectedFile.value = null
        _selectedFileName.value = null
        _uploadState.value = ConverterUploadState()
        uploadObserverJob?.cancel()
        uploadObserverJob = null
    }

    // Accept baseUrl string so this method doesn't directly depend on ConnectivityViewModel's API level
    fun uploadToEpaper(
        file: File,
        baseUrl: String,
        uploadQueuedMessage: String = "Upload queued in Tasks",
        notConnectedMessage: String = "Device not connected",
        uploadSuccessfulMessage: String = "Upload Successful!",
        uploadFailedMessage: String = "Upload failed",
        isConnected: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                if (!isConnected) {
                    _uploadState.value =
                        ConverterUploadState(
                            phase = ConverterUploadPhase.FAILED,
                            error = "Device not connected"
                        )
                    showToast(notConnectedMessage)
                    return@launch
                }

                val fileName = file.name
                val targetPath = "/$fileName"

                _uploadState.value = ConverterUploadState(phase = ConverterUploadPhase.QUEUED)
                val taskId = taskRepository.addTask(android.net.Uri.fromFile(file), fileName, targetPath)
                showToast(uploadQueuedMessage)
                observeUploadTask(taskId, notConnectedMessage, uploadSuccessfulMessage, uploadFailedMessage)
            } catch (e: Exception) {
                _uploadState.value =
                    ConverterUploadState(
                        phase = ConverterUploadPhase.FAILED,
                        error = e.message
                    )
                showToast("$uploadFailedMessage: ${e.message ?: ""}")
            }
        }
    }

    private fun observeUploadTask(
        taskId: String,
        notConnectedMessage: String,
        uploadSuccessfulMessage: String,
        uploadFailedMessage: String
    ) {
        uploadObserverJob?.cancel()
        uploadObserverJob =
            viewModelScope.launch {
                taskRepository.tasks.collect { tasks ->
                    val task = tasks.find { it.id == taskId } ?: return@collect
                    when (task.status) {
                        TaskStatus.QUEUED ->
                            _uploadState.value =
                                ConverterUploadState(phase = ConverterUploadPhase.QUEUED)
                        TaskStatus.UPLOADING ->
                            _uploadState.value =
                                ConverterUploadState(
                                    phase = ConverterUploadPhase.UPLOADING,
                                    progress = task.progress
                                )
                        TaskStatus.COMPLETED -> {
                            _uploadState.value =
                                ConverterUploadState(
                                    phase = ConverterUploadPhase.COMPLETED,
                                    progress = 1f
                                )
                            showToast(uploadSuccessfulMessage)
                            uploadObserverJob?.cancel()
                            uploadObserverJob = null
                        }
                        TaskStatus.FAILED -> {
                            _uploadState.value =
                                ConverterUploadState(
                                    phase = ConverterUploadPhase.FAILED,
                                    error = task.error
                                )
                            val localizedError = localizeUploadError(task.error, notConnectedMessage)
                            showToast("$uploadFailedMessage: $localizedError")
                            uploadObserverJob?.cancel()
                            uploadObserverJob = null
                        }
                        TaskStatus.CANCELLED -> {
                            _uploadState.value =
                                ConverterUploadState(phase = ConverterUploadPhase.CANCELLED)
                            uploadObserverJob?.cancel()
                            uploadObserverJob = null
                        }
                    }
                }
            }
    }

    private fun localizeUploadError(error: String?, notConnectedMessage: String): String {
        return when (error) {
            "Device not connected" -> notConnectedMessage
            null -> ""
            else -> error
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(
            getApplication(),
            message,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun crossPointBookMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "xtch" -> "application/vnd.crosspoint.xtch"
            "xtc" -> "application/vnd.crosspoint.xtc"
            else -> "application/octet-stream"
        }
    }

    fun saveToDownloads(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            android.widget.Toast.makeText(
                getApplication(),
                "Save to Downloads requires Android 10+",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!file.exists()) {
            android.widget.Toast.makeText(
                getApplication(),
                "File not found: ${file.name}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        android.widget.Toast.makeText(
            getApplication(),
            "Saving to Downloads...",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, crossPointBookMimeType(file.name))
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(file).use { input -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Saved to Downloads",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        val currentStatus = _status.value
                        if (currentStatus is ConverterStatus.Preview) {
                            _status.value = currentStatus.copy(isSaved = true)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "Failed to create file in Downloads",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(),
                        "Save failed: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
