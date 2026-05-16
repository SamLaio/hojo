package wtf.anurag.hojo.ui.apps.converter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs the x4converter.rho.sh JavaScript/CREngine WASM pipeline in a headless WebView
 * to convert EPUB files to XTC format.
 *
 * The WebView loads converter-bridge.html from assets, which bundles:
 *  - crengine.js / crengine.wasm  – CREngine EPUB rendering engine (WASM)
 *  - converter.js                 – Core conversion logic (verbatim from x4converter.rho.sh)
 *  - converter-bridge.js          – BridgeAPI and Android callback layer
 *
 * Communication:
 *  Android → JS : evaluateJavascript("BridgeAPI.method(args)")
 *  JS → Android : @JavascriptInterface methods on the "Android" object
 */
class WebViewConverter(private val context: Context) {

    // Max base64 chunk size for EPUB input transfer (~1 MB decoded → ~1.33 MB base64)
    private val EPUB_CHUNK_SIZE = 1_048_576

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // State for ongoing conversion
    private var progressCallback: ((Int, Int) -> Unit)? = null
    private var conversionContinuation: kotlinx.coroutines.CancellableContinuation<File>? = null
    private var htmlConversionContinuation: kotlinx.coroutines.CancellableContinuation<ByteArray>? = null
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var chunkCount = 0
    private var systemCjkFontRegistered = false

    // ─── JavaScript Interface ─────────────────────────────────────────────────

    inner class AndroidBridge {

        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                readyContinuation?.resume(Unit)
                readyContinuation = null
            }
        }

        @JavascriptInterface
        fun onProgress(current: Int, total: Int) {
            mainHandler.post {
                progressCallback?.invoke(current, total)
            }
        }

        @JavascriptInterface
        fun onXtcChunk(base64: String, index: Int, isLast: Boolean) {
            // Decode and write chunk on a background thread to avoid blocking the JS thread
            mainHandler.post {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)

                    // Write to file for EPUB conversion, or accumulate for HTML conversion
                    val ofs = outputStream
                    if (ofs != null) {
                        ofs.write(bytes)
                        chunkCount++
                        if (isLast) {
                            ofs.flush()
                            ofs.close()
                            outputStream = null
                            val file = outputFile ?: run {
                                conversionContinuation?.resumeWithException(
                                    IllegalStateException("Output file not set")
                                )
                                conversionContinuation = null
                                return@post
                            }
                            conversionContinuation?.resume(file)
                            conversionContinuation = null
                        }
                    } else {
                        // HTML conversion: accumulate chunks in memory
                        htmlChunks.add(bytes)
                        if (isLast) {
                            val totalSize = htmlChunks.sumOf { it.size }
                            val assembled = ByteArray(totalSize)
                            var pos = 0
                            for (chunk in htmlChunks) {
                                chunk.copyInto(assembled, pos)
                                pos += chunk.size
                            }
                            htmlChunks.clear()
                            // Strip any prefix bytes before the XTC magic (0x58='X', 0x54='T', 0x43='C')
                            var xtcStart = 0
                            for (i in 0 until minOf(assembled.size - 2, 16)) {
                                if (assembled[i] == 0x58.toByte() &&
                                    assembled[i + 1] == 0x54.toByte() &&
                                    assembled[i + 2] == 0x43.toByte()) {
                                    xtcStart = i
                                    break
                                }
                            }
                            val result = if (xtcStart > 0) assembled.copyOfRange(xtcStart, assembled.size) else assembled
                            htmlConversionContinuation?.resume(result)
                            htmlConversionContinuation = null
                        }
                    }
                } catch (e: Exception) {
                    outputStream?.close()
                    outputStream = null
                    conversionContinuation?.resumeWithException(e)
                    conversionContinuation = null
                    htmlConversionContinuation?.resumeWithException(e)
                    htmlConversionContinuation = null
                }
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            mainHandler.post {
                val ex = RuntimeException(message)
                readyContinuation?.resumeWithException(ex)
                readyContinuation = null
                conversionContinuation?.resumeWithException(ex)
                conversionContinuation = null
                htmlConversionContinuation?.resumeWithException(ex)
                htmlConversionContinuation = null
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebViewConverter", message)
        }
    }

    private val htmlChunks = mutableListOf<ByteArray>()
    private var readyContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private val systemCjkFontCandidates = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansCJK-VF.ttf.ttc",
        "/system/fonts/NotoSansTC-Regular.otf",
        "/system/fonts/NotoSansCJKtc-Regular.otf",
        "/system/fonts/DroidSansFallback.ttf"
    )

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Creates the WebView and waits for CREngine WASM to finish loading.
     * Must be called from the Main thread (or use withContext(Dispatchers.Main)).
     */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            readyContinuation = cont

            val wv = WebView(context)
            wv.alpha = 0f  // Invisible - headless use only
            wv.settings.apply {
                javaScriptEnabled = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            wv.addJavascriptInterface(AndroidBridge(), "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    // Serve converter assets (including pattern files) directly from app assets.
                    // Chromium's XHR stack can't load file:///android_asset/ sub-resources on its own.
                    val assetPrefix = "file:///android_asset/converter/"
                    if (url.startsWith(assetPrefix)) {
                        val assetPath = "converter/" + url.removePrefix(assetPrefix)
                        return try {
                            val stream = context.assets.open(assetPath)
                            val mimeType = when {
                                assetPath.endsWith(".js") -> "application/javascript"
                                assetPath.endsWith(".wasm") -> "application/wasm"
                                assetPath.endsWith(".pattern") -> "application/octet-stream"
                                else -> "application/octet-stream"
                            }
                            WebResourceResponse(mimeType, "utf-8", stream)
                        } catch (e: Exception) {
                            null // Let the default handler return a 404
                        }
                    }
                    return null
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    mainHandler.post {
                        val ex = RuntimeException("WebView error $errorCode: $description at $failingUrl")
                        readyContinuation?.resumeWithException(ex)
                        readyContinuation = null
                    }
                }
            }

            wv.loadUrl("file:///android_asset/converter/converter-bridge.html")
            webView = wv

            cont.invokeOnCancellation { wv.destroy() }
        }
    }

    /**
     * Converts an EPUB file (as raw bytes) to an XTC file.
     * Handles chunked base64 transfer for large files.
     */
    suspend fun convertEpub(
        epubBytes: ByteArray,
        outputFile: File,
        settings: ConverterSettings,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            this@WebViewConverter.conversionContinuation = cont
            this@WebViewConverter.outputFile = outputFile
            this@WebViewConverter.outputStream = FileOutputStream(outputFile)
            this@WebViewConverter.progressCallback = onProgress
            this@WebViewConverter.chunkCount = 0

            val wv = webView ?: run {
                cont.resumeWithException(IllegalStateException("WebView not initialized"))
                return@suspendCancellableCoroutine
            }

            // Apply settings, then load EPUB, then start conversion
            val settingsJson = settingsToJson(settings)

            fun loadEpubChunked() {
                val totalChunks = (epubBytes.size + EPUB_CHUNK_SIZE - 1) / EPUB_CHUNK_SIZE

                if (totalChunks == 1) {
                    // Single chunk - direct load
                    val base64 = Base64.encodeToString(epubBytes, Base64.NO_WRAP)
                    wv.evaluateJavascript("""
                        BridgeAPI.loadEpubBase64('$base64').then(function(info){
                            BridgeAPI.startConversion();
                        }).catch(function(e){ Android.onError(e.message || String(e)); });
                        undefined
                    """.trimIndent()) { _ -> }
                } else {
                    // Multi-chunk load
                    wv.evaluateJavascript("BridgeAPI.beginEpubChunks($totalChunks)") { _ ->
                        var chunkIndex = 0
                        fun sendNextChunk() {
                            if (chunkIndex >= totalChunks) {
                                // All chunks sent - finalize and convert
                                wv.evaluateJavascript("""
                                    BridgeAPI.finishEpubChunks().then(function(info){
                                        BridgeAPI.startConversion();
                                    }).catch(function(e){ Android.onError(e.message || String(e)); });
                                    undefined
                                """.trimIndent()) { _ -> }
                                return
                            }
                            val start = chunkIndex * EPUB_CHUNK_SIZE
                            val end = minOf(start + EPUB_CHUNK_SIZE, epubBytes.size)
                            val chunk = epubBytes.copyOfRange(start, end)
                            val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                            val idx = chunkIndex
                            chunkIndex++
                            wv.evaluateJavascript("BridgeAPI.addEpubChunk('$base64', $idx)") { _ ->
                                sendNextChunk()
                            }
                        }
                        sendNextChunk()
                    }
                }
            }

            fun registerFontChunked(
                fontBytes: ByteArray,
                fontName: String,
                useAsPrimary: Boolean,
                onComplete: () -> Unit
            ) {
                val totalChunks = (fontBytes.size + EPUB_CHUNK_SIZE - 1) / EPUB_CHUNK_SIZE
                wv.evaluateJavascript(
                    "BridgeAPI.beginFontChunks($totalChunks, ${JSONObject.quote(fontName)}, $useAsPrimary)"
                ) { _ ->
                    var chunkIndex = 0
                    fun sendNextChunk() {
                        if (chunkIndex >= totalChunks) {
                            wv.evaluateJavascript("BridgeAPI.finishFontChunks()") { _ -> onComplete() }
                            return
                        }

                        val start = chunkIndex * EPUB_CHUNK_SIZE
                        val end = minOf(start + EPUB_CHUNK_SIZE, fontBytes.size)
                        val chunk = fontBytes.copyOfRange(start, end)
                        val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        val idx = chunkIndex
                        chunkIndex++
                        wv.evaluateJavascript("BridgeAPI.addFontChunk('$base64', $idx)") { _ ->
                            sendNextChunk()
                        }
                    }
                    sendNextChunk()
                }
            }

            fun registerSystemCjkThen(next: () -> Unit) {
                if (systemCjkFontRegistered) {
                    next()
                    return
                }

                val systemFont = systemCjkFontCandidates
                    .map { File(it) }
                    .firstOrNull { it.exists() && it.canRead() }

                if (systemFont == null) {
                    next()
                    return
                }

                try {
                    val fontBytes = systemFont.readBytes()
                    registerFontChunked(fontBytes, systemFont.name, false) {
                        systemCjkFontRegistered = true
                        next()
                    }
                } catch (e: Exception) {
                    next()
                }
            }

            // Register custom font if specified
            fun applyFontAndLoad() {
                registerSystemCjkThen {
                    if (settings.fontFamily.isNotEmpty()) {
                        try {
                            val fontFile = File(settings.fontFamily)
                            val fontBytes = fontFile.readBytes()
                            registerFontChunked(fontBytes, fontFile.name, true) {
                                loadEpubChunked()
                            }
                        } catch (e: Exception) {
                            loadEpubChunked()
                        }
                    } else {
                        loadEpubChunked()
                    }
                }
            }

            // Step 1: Apply settings
            wv.evaluateJavascript("BridgeAPI.applyAndroidSettings(${JSONObject.quote(settingsJson)})") { _ ->
                applyFontAndLoad()
            }

            cont.invokeOnCancellation {
                this@WebViewConverter.conversionContinuation = null
                this@WebViewConverter.outputStream?.close()
                this@WebViewConverter.outputStream = null
            }
        }
    }

    /**
     * Converts HTML content to XTC format (for Quick Link / web articles).
     * Wraps the HTML in a minimal EPUB structure before loading into CREngine.
     */
    suspend fun convertHtml(
        html: String,
        title: String,
        settings: ConverterSettings,
        onProgress: (Int, Int) -> Unit
    ): ByteArray = withContext(Dispatchers.Main) {
        // Wrap HTML in a minimal EPUB zip structure
        val epubBytes = withContext(Dispatchers.IO) {
            createMinimalEpub(html, title)
        }

        suspendCancellableCoroutine { cont ->
            this@WebViewConverter.htmlConversionContinuation = cont
            this@WebViewConverter.outputStream = null  // Use in-memory path
            this@WebViewConverter.progressCallback = onProgress
            htmlChunks.clear()

            val wv = webView ?: run {
                cont.resumeWithException(IllegalStateException("WebView not initialized"))
                return@suspendCancellableCoroutine
            }

            val settingsJson = settingsToJson(settings)
            val totalChunks = (epubBytes.size + EPUB_CHUNK_SIZE - 1) / EPUB_CHUNK_SIZE

            fun startConversion() {
                wv.evaluateJavascript("""
                    BridgeAPI.startConversion().catch(function(e){ Android.onError(e.message || String(e)); });
                    undefined
                """.trimIndent()) { _ -> }
            }

            fun sendChunked() {
                if (totalChunks == 1) {
                    val base64 = Base64.encodeToString(epubBytes, Base64.NO_WRAP)
                    wv.evaluateJavascript("""
                        BridgeAPI.loadEpubBase64('$base64').then(function(){
                            BridgeAPI.startConversion();
                        }).catch(function(e){ Android.onError(e.message || String(e)); });
                        undefined
                    """.trimIndent()) { _ -> }
                } else {
                    wv.evaluateJavascript("BridgeAPI.beginEpubChunks($totalChunks)") { _ ->
                        var chunkIndex = 0
                        fun sendNext() {
                            if (chunkIndex >= totalChunks) {
                                wv.evaluateJavascript("""
                                    BridgeAPI.finishEpubChunks().then(function(){
                                        BridgeAPI.startConversion();
                                    }).catch(function(e){ Android.onError(e.message || String(e)); });
                                    undefined
                                """.trimIndent()) { _ -> }
                                return
                            }
                            val start = chunkIndex * EPUB_CHUNK_SIZE
                            val end = minOf(start + EPUB_CHUNK_SIZE, epubBytes.size)
                            val chunk = epubBytes.copyOfRange(start, end)
                            val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                            val idx = chunkIndex++
                            wv.evaluateJavascript("BridgeAPI.addEpubChunk('$base64', $idx)") { _ -> sendNext() }
                        }
                        sendNext()
                    }
                }
            }

            wv.evaluateJavascript("BridgeAPI.applyAndroidSettings(${JSONObject.quote(settingsJson)})") { _ ->
                sendChunked()
            }

            cont.invokeOnCancellation {
                this@WebViewConverter.htmlConversionContinuation = null
                htmlChunks.clear()
            }
        }
    }

    /** Releases the WebView. Call from ViewModel.onCleared(). */
    fun destroy() {
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun settingsToJson(s: ConverterSettings): String {
        val json = JSONObject()
        // Device
        json.put("deviceWidth", s.deviceWidth)
        json.put("deviceHeight", s.deviceHeight)
        json.put("rotation", s.orientation)
        // Text
        json.put("fontSize", s.fontSize)
        json.put("fontWeight", s.fontWeight)
        json.put("lineHeight", s.lineHeight)   // already in % (80-200)
        json.put("margin", s.margin)
        json.put("fontFace", if (s.fontFamily.isNotEmpty()) "" else s.fontFace)
        json.put("textAlign", s.textAlign)
        json.put("wordSpacing", s.wordSpacing)
        json.put("hyphenation", s.hyphenation)
        json.put("hyphenationLang", s.hyphenationLang)
        json.put("ignoreDocMargins", s.ignoreDocMargins)
        // Image
        json.put("colorMode", if (s.colorMode == ConverterSettings.ColorMode.MONOCHROME) "MONOCHROME" else "GRAYSCALE_4")
        json.put("enableDithering", s.enableDithering)
        json.put("ditherStrength", s.ditherStrength)
        json.put("enableNegative", s.enableNegative)
        // Font rendering (controlled by quality mode in web, hardcode sensible defaults)
        json.put("fontHinting", if (s.colorMode == ConverterSettings.ColorMode.MONOCHROME) 1 else 2)
        json.put("fontAntialiasing", if (s.colorMode == ConverterSettings.ColorMode.MONOCHROME) 0 else 2)
        // Progress bar
        json.put("enableProgressBar", s.enableProgressBar)
        json.put("progressPosition", s.progressPosition)
        json.put("showProgressLine", s.showProgressLine)
        json.put("showChapterMarks", s.showChapterMarks)
        json.put("showChapterProgress", s.showChapterProgress)
        json.put("progressFullWidth", s.progressFullWidth)
        json.put("showPageInfo", s.showPageInfo)
        json.put("showBookPercent", s.showBookPercent)
        json.put("showChapterPage", s.showChapterPage)
        json.put("showChapterPercent", s.showChapterPercent)
        json.put("progressFontSize", s.progressFontSize)
        json.put("progressEdgeMargin", s.progressEdgeMargin)
        json.put("progressSideMargin", s.progressSideMargin)
        return json.toString()
    }

    /**
     * Creates a minimal valid EPUB 2.0 zip from HTML content.
     * CREngine can load this just like a real EPUB file.
     */
    private fun createMinimalEpub(html: String, title: String): ByteArray {
        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$safeTitle</dc:title>
    <dc:identifier id="uid">uid-001</dc:identifier>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="content" href="content.html" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="content"/>
  </spine>
</package>"""

        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="uid-001"/></head>
  <docTitle><text>$safeTitle</text></docTitle>
  <navMap>
    <navPoint id="p1" playOrder="1">
      <navLabel><text>$safeTitle</text></navLabel>
      <content src="content.html"/>
    </navPoint>
  </navMap>
</ncx>"""

        val container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

        val mimeType = "application/epub+zip"

        // Build ZIP in memory
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            // mimetype must be first, uncompressed
            zip.setMethod(java.util.zip.ZipOutputStream.STORED)
            val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
            val mimeEntry = java.util.zip.ZipEntry("mimetype").apply {
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = java.util.zip.CRC32().also { it.update(mimeBytes) }.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            // Switch to deflate for the rest
            zip.setMethod(java.util.zip.ZipOutputStream.DEFLATED)

            fun addEntry(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            addEntry("META-INF/container.xml", container)
            // html is already a full XHTML document serialized by XMLSerializer in JS.
            // Prepend the XML declaration which XMLSerializer omits.
            val xhtmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$html"

            addEntry("OEBPS/content.opf", opf)
            addEntry("OEBPS/toc.ncx", ncx)
            addEntry("OEBPS/content.html", xhtmlContent)
        }

        return baos.toByteArray()
    }
}
