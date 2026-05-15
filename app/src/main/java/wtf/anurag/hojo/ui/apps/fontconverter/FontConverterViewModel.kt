package wtf.anurag.hojo.ui.apps.fontconverter

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.anurag.hojo.data.TaskRepository
import wtf.anurag.hojo.data.model.TaskStatus
import wtf.anurag.hojo.ui.apps.converter.ConverterUploadPhase
import wtf.anurag.hojo.ui.apps.converter.ConverterUploadState

sealed class FontConverterStatus {
    data object Idle : FontConverterStatus()
    data object ReadingFile : FontConverterStatus()
    data class Converting(val current: Int, val total: Int) : FontConverterStatus()
    data class Converted(
            val outputFile: File,
            val isSaved: Boolean = false,
            val savedLocation: String? = null
    ) : FontConverterStatus()
    data class Error(val message: String) : FontConverterStatus()
}

@HiltViewModel
class FontConverterViewModel @Inject constructor(
        application: Application,
        private val taskRepository: TaskRepository
) :
        AndroidViewModel(application) {
    companion object {
        private const val PREFS_NAME = "font_converter"
        private const val PREF_FONT_SIZE = "font_size"
        private const val DEFAULT_FONT_SIZE = 18
    }

    private val preferences by lazy {
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _status = MutableStateFlow<FontConverterStatus>(FontConverterStatus.Idle)
    val status: StateFlow<FontConverterStatus> = _status.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _fontSize =
            MutableStateFlow(
                    preferences.getInt(PREF_FONT_SIZE, DEFAULT_FONT_SIZE).let { stored ->
                        if (stored == 37) DEFAULT_FONT_SIZE else stored
                    }
            )
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _uploadState = MutableStateFlow(ConverterUploadState())
    val uploadState: StateFlow<ConverterUploadState> = _uploadState.asStateFlow()

    private var uploadObserverJob: Job? = null

    fun updateFontSize(value: Int) {
        val normalized = value.coerceIn(8, 96)
        _fontSize.value = normalized
        preferences.edit().putInt(PREF_FONT_SIZE, normalized).apply()
    }

    fun decreaseFontSize() {
        updateFontSize(_fontSize.value - 1)
    }

    fun increaseFontSize() {
        updateFontSize(_fontSize.value + 1)
    }

    fun selectFont(uri: Uri) {
        viewModelScope.launch {
            try {
                val fileName = getFileName(uri)
                _selectedFileName.value = fileName
                _status.value = FontConverterStatus.ReadingFile

                val tempFont = copyToTempFile(uri, fileName)
                val outputName =
                        "${fileName.substringBeforeLast('.', fileName)}-${_fontSize.value}.epdfont"

                _status.value = FontConverterStatus.Converting(0, 0)
                val bytes =
                        withContext(Dispatchers.Default) {
                            EpdfFontConverter(getApplication())
                                    .convert(
                                            tempFont,
                                            EpdfFontOptions(sizePx = _fontSize.value)
                                    ) { progress ->
                                        _status.value =
                                                FontConverterStatus.Converting(
                                                        progress.current,
                                                        progress.total
                                                )
                                    }
                        }

                val outputFile = writeToCache(outputName, bytes)
                _status.value = FontConverterStatus.Converted(outputFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _status.value = FontConverterStatus.Error(e.message ?: "Font conversion failed")
            }
        }
    }

    fun reset() {
        _selectedFileName.value = null
        _uploadState.value = ConverterUploadState()
        uploadObserverJob?.cancel()
        uploadObserverJob = null
        _status.value = FontConverterStatus.Idle
    }

    private suspend fun copyToTempFile(uri: Uri, fileName: String): File =
            withContext(Dispatchers.IO) {
                val extension = fileName.substringAfterLast('.', "font")
                val tempFile = File.createTempFile("font_converter_", ".$extension", getApplication<Application>().cacheDir)
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: error("Failed to open font file")
                tempFile
            }

    private suspend fun writeToCache(
            outputName: String,
            bytes: ByteArray
    ): File =
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().cacheDir, outputName).also { outputFile ->
                    outputFile.writeBytes(bytes)
                }
            }

    fun saveToDownloads(file: File) {
        if (!file.exists()) {
            showToast("File not found: ${file.name}")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedLocation = saveFileToDownloads(file)
                val current = _status.value
                if (current is FontConverterStatus.Converted && current.outputFile == file) {
                    _status.value =
                            current.copy(isSaved = true, savedLocation = savedLocation)
                }
                showToast("Saved")
            } catch (e: Exception) {
                showToast("Save failed: ${e.message ?: ""}")
            }
        }
    }

    private fun saveFileToDownloads(file: File): String {
        val resolver = getApplication<Application>().contentResolver
        val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.crosspoint.epdffont")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create output file")
        resolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
                ?: error("Failed to write output file")
        return "${Environment.DIRECTORY_DOWNLOADS}/${file.name}"
    }

    fun uploadToEpaper(
            file: File,
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

                if (!file.exists()) {
                    throw IllegalStateException("File not found: ${file.name}")
                }

                _uploadState.value = ConverterUploadState(phase = ConverterUploadPhase.QUEUED)
                val taskId =
                        taskRepository.addTask(
                                android.net.Uri.fromFile(file),
                                file.name,
                                "/${file.name}"
                        )
                showToast(uploadQueuedMessage)
                observeUploadTask(
                        taskId,
                        notConnectedMessage,
                        uploadSuccessfulMessage,
                        uploadFailedMessage
                )
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
                                            ConverterUploadState(
                                                    phase = ConverterUploadPhase.QUEUED
                                            )
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
                                showToast(
                                        "$uploadFailedMessage: ${
                                            localizeUploadError(task.error, notConnectedMessage)
                                        }"
                                )
                                uploadObserverJob?.cancel()
                                uploadObserverJob = null
                            }
                            TaskStatus.CANCELLED -> {
                                _uploadState.value =
                                        ConverterUploadState(
                                                phase = ConverterUploadPhase.CANCELLED
                                        )
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
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor =
                    getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }

        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result ?: "font.ttf"
    }
}
