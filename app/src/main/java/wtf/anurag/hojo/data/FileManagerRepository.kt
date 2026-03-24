package wtf.anurag.hojo.data

import android.util.Log
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import wtf.anurag.hojo.data.model.FileItem
import wtf.anurag.hojo.data.model.StorageStatus
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FileManagerRepository @Inject constructor(private val client: OkHttpClient) {
    private val gson = Gson()
    private val TAG = "FileManagerRepo"

    suspend fun fetchList(baseUrl: String, path: String): List<FileItem> =
            withContext(Dispatchers.IO) {
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                val url = "$baseUrl/api/files?path=$encodedPath"
                Log.d(TAG, "fetchList -> GET $url")

                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "fetchList -> response code: ${response.code}")
                    if (!response.isSuccessful) throw IOException("List failed: ${response.code}")
                    val json = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<FileItem>>() {}.type
                    val list: List<FileItem> = gson.fromJson(json, type)
                    list.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                }
            }

    suspend fun fetchStatus(baseUrl: String): StorageStatus =
            withContext(Dispatchers.IO) {
                val url = "$baseUrl/api/status"
                Log.d(TAG, "fetchStatus -> GET $url")

                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
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
                client.newCall(request).execute().use { response ->
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

                client.newCall(request).execute().use { response ->
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

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Rename failed: ${response.code}")
                }
            }

    suspend fun downloadFile(baseUrl: String, remotePath: String, destFile: File) =
            withContext(Dispatchers.IO) {
                val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
                val url = "$baseUrl/download?path=$encodedPath"
                Log.d(TAG, "downloadFile -> GET $url")

                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
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

                client.newCall(request).execute().use { response ->
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
     *   3. Send BINARY chunks of the file data
     *   4. Receive TEXT "PROGRESS:n:total" updates (optional)
     *   5. Receive TEXT "DONE" on success, "ERROR:<msg>" on failure
     *
     * @param targetPath Full path on device, e.g. "/books/myfile.xtc"
     */
    suspend fun uploadFileWebSocket(
            baseUrl: String,
            file: File,
            targetPath: String,
            onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ) =
            withContext(Dispatchers.IO) {
                val host = baseUrl.removePrefix("http://").removePrefix("https://")
                val wsUrl = "ws://$host:81"
                val filename = targetPath.substringAfterLast('/')
                val dir = targetPath.substringBeforeLast('/').ifEmpty { "/" }
                val fileSize = file.length()

                suspendCancellableCoroutine<Unit> { cont ->
                    val request = Request.Builder().url(wsUrl).build()

                    val listener = object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            // Send START command then immediately stream the file
                            ws.send("START:$filename:$fileSize:$dir")
                            try {
                                val buffer = ByteArray(8192)
                                file.inputStream().use { stream ->
                                    var bytesRead: Int
                                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                                        if (!ws.send(buffer.toByteString(0, bytesRead))) {
                                            throw IOException("WebSocket send queue full")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                ws.cancel()
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            when {
                                text == "DONE" -> {
                                    ws.close(1000, null)
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                text.startsWith("ERROR:") -> {
                                    val msg = text.removePrefix("ERROR:")
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
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }

                    val ws = client.newWebSocket(request, listener)
                    cont.invokeOnCancellation { ws.cancel() }
                }
            }
}
