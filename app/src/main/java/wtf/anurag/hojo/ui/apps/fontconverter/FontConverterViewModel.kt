package wtf.anurag.hojo.ui.apps.fontconverter

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class FontConverterStatus {
    data object Idle : FontConverterStatus()
    data object ReadingFile : FontConverterStatus()
    data class Converting(val current: Int, val total: Int) : FontConverterStatus()
    data class Saved(val fileName: String, val location: String) : FontConverterStatus()
    data class Error(val message: String) : FontConverterStatus()
}

@HiltViewModel
class FontConverterViewModel @Inject constructor(application: Application) :
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

                val savedLocation = saveBesideSourceOrDownloads(uri, outputName, bytes)
                _status.value = FontConverterStatus.Saved(outputName, savedLocation)
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

    private suspend fun saveBesideSourceOrDownloads(
            sourceUri: Uri,
            outputName: String,
            bytes: ByteArray
    ): String =
            withContext(Dispatchers.IO) {
                saveBesideSource(sourceUri, outputName, bytes)
                        ?: saveToDownloads(outputName, bytes)
            }

    private fun saveBesideSource(sourceUri: Uri, outputName: String, bytes: ByteArray): String? {
        val resolver = getApplication<Application>().contentResolver

        if (sourceUri.scheme == "file") {
            val sourceFile = File(sourceUri.path ?: return null)
            val parent = sourceFile.parentFile ?: return null
            File(parent, outputName).writeBytes(bytes)
            return parent.absolutePath
        }

        if (!DocumentsContract.isDocumentUri(getApplication(), sourceUri)) return null
        val authority = sourceUri.authority ?: return null
        val documentId = DocumentsContract.getDocumentId(sourceUri)
        val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isBlank() || parentId == documentId) return null

        return try {
            val parentUri = DocumentsContract.buildDocumentUri(authority, parentId)
            val outputUri =
                    DocumentsContract.createDocument(
                            resolver,
                            parentUri,
                            "application/octet-stream",
                            outputName
                    ) ?: return null
            resolver.openOutputStream(outputUri, "w")?.use { it.write(bytes) } ?: return null
            outputUri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToDownloads(outputName: String, bytes: ByteArray): String {
        val resolver = getApplication<Application>().contentResolver
        val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create output file")
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("Failed to write output file")
        return "${Environment.DIRECTORY_DOWNLOADS}/$outputName"
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
