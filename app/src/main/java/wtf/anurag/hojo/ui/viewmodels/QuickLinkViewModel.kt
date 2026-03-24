package wtf.anurag.hojo.ui.viewmodels

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wtf.anurag.hojo.data.ConnectivityRepository
import wtf.anurag.hojo.data.FileManagerRepository
import wtf.anurag.hojo.ui.apps.converter.ConverterSettings
import wtf.anurag.hojo.ui.apps.converter.WebViewConverter
import javax.inject.Inject
import kotlin.coroutines.resume

data class ReadabilityResult(val title: String, val content: String)

@HiltViewModel
class QuickLinkViewModel @Inject constructor(
    application: Application,
    private val repository: FileManagerRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val taskRepository: wtf.anurag.hojo.data.TaskRepository,
) : AndroidViewModel(application) {

    private val _quickLinkVisible = MutableStateFlow(false)
    val quickLinkVisible: StateFlow<Boolean> = _quickLinkVisible.asStateFlow()

    private val _quickLinkUrl = MutableStateFlow("")
    val quickLinkUrl: StateFlow<String> = _quickLinkUrl.asStateFlow()

    private val _converting = MutableStateFlow(false)
    val converting: StateFlow<Boolean> = _converting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _previewFile = MutableStateFlow<File?>(null)
    val previewFile: StateFlow<File?> = _previewFile.asStateFlow()

    private var webViewConverter: WebViewConverter? = null
    private var converterInitialized = false

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

    fun setQuickLinkVisible(visible: Boolean) {
        _quickLinkVisible.value = visible
    }

    fun setQuickLinkUrl(url: String) {
        _quickLinkUrl.value = url
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun getReadabilityJsFile(): File {
        val dir = File(getApplication<Application>().filesDir, "readability_lib")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "readability.js")
    }

    private suspend fun ensureReadabilityJsExists(): String {
        return withContext(Dispatchers.IO) {
            val file = getReadabilityJsFile()

            // Check if file exists and has content
            if (!file.exists() || file.length() == 0L) {
                // Download from Mozilla's GitHub
                val url = "https://raw.githubusercontent.com/mozilla/readability/main/Readability.js"
                val content = URL(url).readText()
                FileOutputStream(file).use { it.write(content.toByteArray()) }
            }

            // Read and return the content
            file.readText()
        }
    }

    private suspend fun parseUrlWithWebView(context: Context, url: String): ReadabilityResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                webView.alpha = 0f

                // Only process the first onPageFinished — it fires once per frame/redirect
                var hasFinished = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (hasFinished || !continuation.isActive) return
                        hasFinished = true

                        viewModelScope.launch {
                            try {
                                val readabilityJsLibString = ensureReadabilityJsExists()

                                val jsLogic = """
                                    $readabilityJsLibString

                                    (function() {
                                        try {
                                            var documentClone = document.cloneNode(true);
                                            var article = new Readability(documentClone).parse();
                                            if (!article) return JSON.stringify({ error: 'Readability returned null' });

                                            // Serialize content to XHTML so CREngine can parse it as
                                            // application/xhtml+xml. DOMParser+XMLSerializer handles
                                            // void-element closing (<br/>, <img/>, etc.) automatically.
                                            var parser = new DOMParser();
                                            var contentDoc = parser.parseFromString(
                                                '<html><head><title>' + article.title + '</title></head><body>' + article.content + '</body></html>',
                                                'text/html'
                                            );
                                            var serializer = new XMLSerializer();
                                            var xhtml = serializer.serializeToString(contentDoc);

                                            return JSON.stringify({
                                                title: article.title,
                                                content: xhtml
                                            });
                                        } catch(e) {
                                            return JSON.stringify({ error: e.toString() });
                                        }
                                    })();
                                """

                                view?.evaluateJavascript(jsLogic) { result ->
                                    try {
                                        if (!continuation.isActive) return@evaluateJavascript
                                        if (result == "null") {
                                            continuation.resume(ReadabilityResult("Quick Link", ""))
                                            return@evaluateJavascript
                                        }

                                        val jsonStr = result.substring(1, result.length - 1)
                                            .replace("\\\"", "\"")
                                            .replace("\\\\", "\\")
                                            .replace("\\n", "\n")
                                            .replace("\\r", "\r")
                                            .replace("\\t", "\t")

                                        val json = JSONObject(jsonStr)
                                        val title = json.optString("title", "Quick Link")
                                        val content = json.optString("content", "")

                                        continuation.resume(ReadabilityResult(title, content))
                                    } catch (e: Exception) {
                                        if (continuation.isActive) {
                                            continuation.resume(ReadabilityResult("Quick Link", ""))
                                        }
                                    } finally {
                                        webView.destroy()
                                    }
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(ReadabilityResult("Quick Link", ""))
                                }
                                webView.destroy()
                            }
                        }
                    }
                }

                webView.loadUrl(url)

                continuation.invokeOnCancellation {
                    webView.destroy()
                }
            }
        }
    }

    /**
     * Replaces <img src="https://..."> with base64 data URLs so images survive
     * offline EPUB conversion inside CREngine WASM. Skips anything already a
     * data URL, and silently drops images that fail to download.
     */
    private fun inlineImagesAsDataUrls(html: String): String {
        val imgSrcRegex = Regex("""(<img\b[^>]*?\bsrc=)(["'])([^"'#][^"']*)(\2)""", RegexOption.IGNORE_CASE)
        return imgSrcRegex.replace(html) { match ->
            val src = match.groupValues[3]
            if (src.startsWith("data:")) return@replace match.value
            try {
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val contentType = conn.contentType?.substringBefore(";")?.trim()
                    ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > 4 * 1024 * 1024) return@replace match.value // skip >4 MB images
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                "${match.groupValues[1]}${match.groupValues[2]}data:$contentType;base64,$b64${match.groupValues[4]}"
            } catch (e: Exception) {
                match.value // keep original src on error, converter will just skip it
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleConvert() {
        if (_quickLinkUrl.value.isBlank()) return
        _converting.value = true
        _errorMessage.value = null // Clear previous errors

        viewModelScope.launch {
            try {
                // 1. Prepare network for internet access (finds cellular/WiFi with internet)
                val hasInternet = connectivityRepository.prepareNetworkForApiRequest()
                if (!hasInternet) {
                    _errorMessage.value = "No internet connection available. If you're using hotspot mode, please ensure mobile data is enabled."
                    _converting.value = false
                    return@launch
                }

                // 2. Load URL in WebView and extract content with Readability.js
                val readabilityResult = parseUrlWithWebView(
                    context = getApplication(),
                    url = _quickLinkUrl.value
                )

                val title = readabilityResult.title
                // Inline images as data URLs so CREngine can render them offline
                val cleanedHtml = withContext(Dispatchers.IO) {
                    inlineImagesAsDataUrls(readabilityResult.content)
                }

                // 3. Convert HTML directly to XTC format via WebView converter
                val converter = getConverter()
                val xtcData = converter.convertHtml(
                    html = cleanedHtml,
                    title = title,
                    settings = ConverterSettings(colorMode = ConverterSettings.ColorMode.MONOCHROME)
                ) { _, _ -> }

                // Save to temp file
                val fileName = "${title.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}.xtc"
                val tempFile = File(getApplication<Application>().cacheDir, fileName)
                withContext(Dispatchers.IO) { FileOutputStream(tempFile).use { it.write(xtcData) } }

                // 5. Rebind to Epaper network for device communication
                connectivityRepository.bindToEpaperNetwork()

                // Show preview
                _previewFile.value = tempFile
                _quickLinkVisible.value = false
                _quickLinkUrl.value = ""
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value =
                        when {
                            e.message?.contains("failed to connect", ignoreCase = true) == true ->
                                    "Failed to connect. Please check your internet connection."
                            e.message?.contains("No pages rendered", ignoreCase = true) == true ->
                                    "Failed to convert page. The content may be empty or unsupported."
                            e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                                    "No internet connection. If using hotspot, enable mobile data."
                            else -> "Error: ${e.message ?: "Unknown error occurred"}"
                        }
                // Ensure rebind on error
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectivityRepository.bindToEpaperNetwork()
                }
            } finally {
                _converting.value = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun uploadPreviewFile() {
        val file = _previewFile.value ?: return

        viewModelScope.launch {
            try {
                _converting.value = true
                
                // Queue the task
                val targetPath = "/books/${file.name}"
                taskRepository.addTask(android.net.Uri.fromFile(file), file.name, targetPath)

                // Close preview immediately
                _previewFile.value = null
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Failed to queue upload: ${e.message}"
            } finally {
                _converting.value = false
            }
        }
    }

    fun closePreview() {
        _previewFile.value = null
    }
}
