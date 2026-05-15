package wtf.anurag.hojo.data

import android.util.Log
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import wtf.anurag.hojo.connectivity.EpaperConnectivityManager
import wtf.anurag.hojo.data.model.FileItem
import wtf.anurag.hojo.data.model.StorageStatus
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FileManagerRepository
@Inject
constructor(
        private val client: OkHttpClient,
        private val connectivityManager: EpaperConnectivityManager
) {
    private val gson = Gson()
    private val TAG = "FileManagerRepo"
    private val fileListType = object : TypeToken<List<FileItem>>() {}.type

    companion object {
        private const val FILE_LIST_PAGE_SIZE = 100
        private const val MAX_FILE_LIST_PAGES = 100
        private const val FILE_LIST_RESPONSE_PREVIEW_LENGTH = 240
        private const val WEBSOCKET_CHUNK_SIZE = 4096
        private const val WEBSOCKET_MAX_QUEUED_BYTES = 256 * 1024L
        private const val WEBSOCKET_QUEUE_POLL_DELAY_MS = 20L
        private const val WEBSOCKET_QUEUE_DRAIN_TIMEOUT_MS = 30_000L
    }

    private suspend fun deviceClientOrFallback(): OkHttpClient {
        val network = connectivityManager.getDeviceNetworkOrNull()
        return if (network != null) {
            connectivityManager.createNetworkBoundClient(network)
        } else {
            Log.d(TAG, "No device network available, falling back to shared OkHttpClient")
            client
        }
    }

    suspend fun fetchList(baseUrl: String, path: String): List<FileItem> =
            withContext(Dispatchers.IO) {
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                val allItems = mutableListOf<FileItem>()
                var firstPageSignature: List<String>? = null
                val httpClient = deviceClientOrFallback()
                var page = 0

                while (page < MAX_FILE_LIST_PAGES) {
                    val offset = page * FILE_LIST_PAGE_SIZE
                    val pageItems = fetchListPage(httpClient, baseUrl, path, encodedPath, offset, page == 0)

                    val pageSignature = pageItems.map { "${it.name}:${it.isDirectory}:${it.size}" }
                    if (page == 0) {
                        firstPageSignature = pageSignature
                    } else if (pageSignature == firstPageSignature) {
                        Log.d(TAG, "fetchList -> endpoint ignored pagination, stopping after first page")
                        break
                    }

                    allItems += pageItems

                    if (pageItems.size < FILE_LIST_PAGE_SIZE || pageItems.isEmpty()) {
                        break
                    }

                    page++
                }

                allItems.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            }

    private fun fetchListPage(
            httpClient: OkHttpClient,
            baseUrl: String,
            path: String,
            encodedPath: String,
            offset: Int,
            allowFirstPageFallbacks: Boolean
    ): List<FileItem> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val urls =
                buildList {
                    add(
                            "$normalizedBaseUrl/api/files?path=$encodedPath&offset=$offset&limit=$FILE_LIST_PAGE_SIZE"
                    )
                    if (allowFirstPageFallbacks) {
                        add("$normalizedBaseUrl/api/files?path=$encodedPath")
                        add("$normalizedBaseUrl/api/files?path=$path")
                    }
                }.distinct()

        var lastFailure: IOException? = null
        for (url in urls) {
            try {
                Log.d(TAG, "fetchList -> GET $url")
                val request = Request.Builder().url(url).build()
                return httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "fetchList -> response code: ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("List failed: ${response.code} ${body.toResponsePreview()}")
                    }
                    parseFileListResponse(body)
                }
            } catch (e: IOException) {
                lastFailure = e
                Log.w(TAG, "fetchList -> failed for $url: ${e.message}")
            } catch (e: IllegalStateException) {
                lastFailure = IOException(e.message ?: "Invalid file list response", e)
                Log.w(TAG, "fetchList -> invalid response for $url: ${e.message}")
            }
        }

        throw lastFailure ?: IOException("List failed")
    }

    private fun parseFileListResponse(json: String): List<FileItem> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()

        val root =
                try {
                    JsonParser.parseString(trimmed)
                } catch (e: Exception) {
                    throw IOException("Invalid file list response: ${trimmed.toResponsePreview()}", e)
                }

        val array =
                when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject -> root.asJsonObject.findFileArray()
                    root.isJsonPrimitive && root.asJsonPrimitive.isString ->
                            throw IOException("Device returned: ${root.asString.toResponsePreview()}")
                    else ->
                            throw IOException(
                                    "Unexpected file list response: ${trimmed.toResponsePreview()}"
                            )
                }

        return array.mapNotNull { element -> element.toFileItemOrNull() }
    }

    private fun JsonObject.findFileArray(): JsonArray {
        val arrayKeys = listOf("files", "items", "data", "list", "entries", "results")
        for (key in arrayKeys) {
            val element = get(key)
            if (element?.isJsonArray == true) return element.asJsonArray
        }

        val messageKeys = listOf("error", "message", "msg", "detail")
        for (key in messageKeys) {
            val element = get(key)
            if (element?.isJsonPrimitive == true && element.asJsonPrimitive.isString) {
                throw IOException("Device returned: ${element.asString.toResponsePreview()}")
            }
        }

        throw IOException("Unexpected file list response: ${toString().toResponsePreview()}")
    }

    private fun JsonElement.toFileItemOrNull(): FileItem? {
        if (!isJsonObject) return null
        val item = asJsonObject

        val name = item.stringValue("name", "filename", "fileName", "path") ?: return null
        val isDirectory =
                item.booleanValue("isDirectory", "isDir", "directory", "dir", "folder")
                        ?: item.stringValue("type", "kind")?.let { type ->
                            type.equals("folder", ignoreCase = true) ||
                                    type.equals("directory", ignoreCase = true) ||
                                    type.equals("dir", ignoreCase = true)
                        }
                        ?: false
        val isEpub =
                item.booleanValue("isEpub", "epub")
                        ?: (!isDirectory && name.endsWith(".epub", ignoreCase = true))
        val size = item.longValue("size", "bytes", "length")

        return FileItem(name = name, isDirectory = isDirectory, isEpub = isEpub, size = size)
    }

    private fun JsonObject.stringValue(vararg keys: String): String? {
        for (key in keys) {
            val element = get(key)
            if (element?.isJsonPrimitive == true) {
                val primitive = element.asJsonPrimitive
                if (primitive.isString) return primitive.asString
            }
        }
        return null
    }

    private fun JsonObject.booleanValue(vararg keys: String): Boolean? {
        for (key in keys) {
            val element = get(key)
            if (element?.isJsonPrimitive == true) {
                val primitive = element.asJsonPrimitive
                if (primitive.isBoolean) return primitive.asBoolean
                if (primitive.isString) {
                    when (primitive.asString.lowercase()) {
                        "true", "1", "yes", "folder", "directory", "dir" -> return true
                        "false", "0", "no", "file" -> return false
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.longValue(vararg keys: String): Long? {
        for (key in keys) {
            val element = get(key)
            if (element?.isJsonPrimitive == true) {
                val primitive = element.asJsonPrimitive
                if (primitive.isNumber) return primitive.asLong
                if (primitive.isString) return primitive.asString.toLongOrNull()
            }
        }
        return null
    }

    private fun String.toResponsePreview(): String {
        val singleLine = replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= FILE_LIST_RESPONSE_PREVIEW_LENGTH) {
            singleLine
        } else {
            "${singleLine.take(FILE_LIST_RESPONSE_PREVIEW_LENGTH)}..."
        }
    }

    private fun buildUploadWebSocketUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val normalized =
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    trimmed
                } else {
                    "http://$trimmed"
                }

        normalized.toHttpUrlOrNull()?.let { httpUrl ->
            val host = httpUrl.host.let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
            return "ws://$host:81/"
        }

        val host =
                trimmed
                        .removePrefix("http://")
                        .removePrefix("https://")
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore(':')
        return "ws://$host:81/"
    }

    private fun sanitizeWebSocketFilename(filename: String): String {
        val sanitized = filename.replace(Regex("""[:\\/\r\n]"""), "_").trim()
        return sanitized.ifBlank { "upload.bin" }
    }

    suspend fun fetchStatus(baseUrl: String): StorageStatus =
            withContext(Dispatchers.IO) {
                val url = "$baseUrl/api/status"
                Log.d(TAG, "fetchStatus -> GET $url")

                val request = Request.Builder().url(url).build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "fetchStatus -> response code: ${response.code}")
                    if (!response.isSuccessful) throw IOException("Status failed: ${response.code}")
                    val json = response.body?.string()
                    gson.fromJson(json, StorageStatus::class.java)
                }
            }

    suspend fun createFolder(baseUrl: String, path: String) =
            withContext(Dispatchers.IO) {
                val parentPath = path.substringBeforeLast('/', "/").ifEmpty { "/" }
                val folderName = path.substringAfterLast('/')

                val body =
                        MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("path", parentPath)
                                .addFormDataPart("name", folderName)
                                .build()

                val request = Request.Builder().url("$baseUrl/mkdir").post(body).build()

                Log.d(TAG, "createFolder -> POST $baseUrl/mkdir with path=$parentPath name=$folderName")
                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "createFolder -> response code: ${response.code}")
                    if (!response.isSuccessful) throw IOException("Create failed: ${response.code}")
                }
            }

    suspend fun deleteItem(baseUrl: String, path: String, isDirectory: Boolean = false) =
            withContext(Dispatchers.IO) {
                val type = if (isDirectory) "folder" else "file"
                val body = "path=${URLEncoder.encode(path, "UTF-8")}&type=$type"
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType())

                val request = Request.Builder().url("$baseUrl/delete").post(body).build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Delete failed: ${response.code}")
                }
            }

    suspend fun renameItem(baseUrl: String, from: String, to: String) =
            withContext(Dispatchers.IO) {
                val newName = to.substringAfterLast('/')

                val body =
                        MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("path", from)
                                .addFormDataPart("name", newName)
                                .build()

                val request = Request.Builder().url("$baseUrl/rename").post(body).build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Rename failed: ${response.code}")
                }
            }

    suspend fun moveItem(baseUrl: String, from: String, destinationFolder: String) =
            withContext(Dispatchers.IO) {
                val body =
                        FormBody.Builder()
                                .add("path", from)
                                .add("dest", destinationFolder.ifBlank { "/" })
                                .build()

                val request = Request.Builder().url("$baseUrl/move").post(body).build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Move failed: ${response.code}")
                }
            }

    suspend fun downloadFile(baseUrl: String, remotePath: String, destFile: File) =
            withContext(Dispatchers.IO) {
                val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
                val url = "$baseUrl/download?path=$encodedPath"
                Log.d(TAG, "downloadFile -> GET $url")

                val request = Request.Builder().url(url).build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "downloadFile -> response code: ${response.code}")
                    if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Empty response body")
                }
            }

    suspend fun uploadFile(
            baseUrl: String,
            file: File,
            targetPath: String,
            onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ) =
            withContext(Dispatchers.IO) {
                val parentPath = targetPath.substringBeforeLast('/', "").ifEmpty { "/" }
                val fileName = targetPath.substringAfterLast('/')
                val encodedParentPath = URLEncoder.encode(parentPath, "UTF-8")

                val extension = file.extension
                val mimeType =
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                ?: "application/octet-stream"

                val fileRequestBody =
                        if (onProgress != null) {
                            ProgressRequestBody(file, mimeType.toMediaType(), onProgress)
                        } else {
                            file.asRequestBody(mimeType.toMediaType())
                        }

                val body =
                        MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("file", fileName, fileRequestBody)
                                .build()

                val request =
                        Request.Builder()
                                .url("$baseUrl/upload?path=$encodedParentPath")
                                .post(body)
                                .build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Upload failed: ${response.code}")
                }
            }

    /**
     * Uploads a file via the device's WebSocket binary upload protocol (port 81).
     * This is a clean binary transfer with no multipart framing, avoiding the
     * \r\n prefix that the ESP32's multipart handler prepends to file content.
     *
     * Protocol:
     *   1. Connect to ws://host:81
     *   2. Send TEXT "START:<filename>:<size>:<dir>"
     *   3. Wait for TEXT "READY"
     *   4. Send BINARY chunks of the file data
     *   5. Receive TEXT "PROGRESS:n:total" updates (optional)
     *   6. Receive TEXT "DONE" on success, "ERROR:<msg>" on failure
     *
     * @param targetPath Full path on device, e.g. "/myfile.xtch"
     */
    suspend fun uploadFileWebSocket(
            baseUrl: String,
            file: File,
            targetPath: String,
            onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ) =
            withContext(Dispatchers.IO) {
                val wsUrl = buildUploadWebSocketUrl(baseUrl)
                val filename = sanitizeWebSocketFilename(targetPath.substringAfterLast('/'))
                val dir = targetPath.substringBeforeLast('/').ifEmpty { "/" }
                val fileSize = file.length()
                val wsClient = deviceClientOrFallback()

                suspendCancellableCoroutine<Unit> { cont ->
                    val request = Request.Builder().url(wsUrl).build()
                    var uploadStarted = false
                    var uploadJob: Job? = null

                    suspend fun waitForSendQueue(ws: WebSocket) {
                        var waitedMs = 0L
                        while (ws.queueSize() > WEBSOCKET_MAX_QUEUED_BYTES) {
                            delay(WEBSOCKET_QUEUE_POLL_DELAY_MS)
                            waitedMs += WEBSOCKET_QUEUE_POLL_DELAY_MS
                            if (waitedMs >= WEBSOCKET_QUEUE_DRAIN_TIMEOUT_MS) {
                                throw IOException("WebSocket send queue stalled")
                            }
                        }
                    }

                    val listener = object : WebSocketListener() {
                        private suspend fun streamFile(ws: WebSocket) {
                            try {
                                val buffer = ByteArray(WEBSOCKET_CHUNK_SIZE)
                                var bytesQueued = 0L
                                file.inputStream().use { stream ->
                                    var bytesRead: Int
                                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                                        waitForSendQueue(ws)
                                        if (!ws.send(buffer.toByteString(0, bytesRead))) {
                                            throw IOException("WebSocket send queue full")
                                        }
                                        bytesQueued += bytesRead
                                        onProgress?.invoke(bytesQueued, fileSize)
                                    }
                                }
                            } catch (e: Exception) {
                                ws.cancel()
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }

                        override fun onOpen(ws: WebSocket, response: Response) {
                            if (!ws.send("START:$filename:$fileSize:$dir") && cont.isActive) {
                                cont.resumeWithException(IOException("WebSocket send queue full"))
                            }
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            when {
                                text == "READY" -> {
                                    if (!uploadStarted) {
                                        uploadStarted = true
                                        uploadJob =
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    streamFile(ws)
                                                }
                                    }
                                }
                                text == "DONE" -> {
                                    uploadJob?.cancel()
                                    ws.close(1000, null)
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                text.startsWith("ERROR:") -> {
                                    val msg = text.removePrefix("ERROR:")
                                    uploadJob?.cancel()
                                    ws.close(1000, null)
                                    if (cont.isActive) cont.resumeWithException(
                                        IOException("Device upload error: $msg")
                                    )
                                }
                                text.startsWith("PROGRESS:") -> {
                                    if (onProgress != null) {
                                        val parts = text.split(":")
                                        if (parts.size >= 3) {
                                            val received = parts[1].toLongOrNull() ?: 0L
                                            val total = parts[2].toLongOrNull() ?: fileSize
                                            onProgress(received, total)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                            uploadJob?.cancel()
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }

                    val ws = wsClient.newWebSocket(request, listener)
                    cont.invokeOnCancellation {
                        uploadJob?.cancel()
                        ws.cancel()
                    }
                }
            }
}
