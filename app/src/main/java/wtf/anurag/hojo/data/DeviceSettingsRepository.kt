package wtf.anurag.hojo.data

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import wtf.anurag.hojo.connectivity.EpaperConnectivityManager
import wtf.anurag.hojo.data.model.DeviceSetting

class DeviceSettingsRepository
@Inject
constructor(
        private val client: OkHttpClient,
        private val connectivityManager: EpaperConnectivityManager
) {
    private val TAG = "DeviceSettingsRepo"

    companion object {
        private const val RESPONSE_PREVIEW_LENGTH = 240
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

    suspend fun fetchSettings(baseUrl: String): List<DeviceSetting> =
            withContext(Dispatchers.IO) {
                val url = "${baseUrl.trimEnd('/')}/api/settings"
                Log.d(TAG, "fetchSettings -> GET $url")

                val request =
                        Request.Builder()
                                .url(url)
                                .header("Accept", "application/json")
                                .build()
                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "fetchSettings -> response code: ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(
                                "Settings fetch failed: ${response.code} ${body.toResponsePreview()}"
                        )
                    }
                    parseSettingsResponse(body, url)
                }
            }

    suspend fun updateSettings(baseUrl: String, changes: Map<String, Any>) =
            withContext(Dispatchers.IO) {
                val body = JSONObject().apply {
                    changes.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> put(key, if (value) 1 else 0)
                            else -> put(key, value)
                        }
                    }
                }.toString()

                val url = "${baseUrl.trimEnd('/')}/api/settings"
                Log.d(TAG, "updateSettings -> POST $url body=$body")

                val request =
                        Request.Builder()
                                .url(url)
                                .header("Accept", "application/json")
                                .post(body.toRequestBody("application/json".toMediaType()))
                                .build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "updateSettings -> response code: ${response.code}")
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.toResponsePreview() ?: response.code.toString()
                        throw IOException("Settings save failed: $err")
                    }
                }
            }

    private fun parseSettingsResponse(body: String, url: String): List<DeviceSetting> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("<")) {
            throw IOException(
                    "Settings API returned HTML instead of JSON. Check that $url points to the CrossPoint device API."
            )
        }

        val arr =
                try {
                    when {
                        trimmed.startsWith("[") -> JSONArray(trimmed)
                        trimmed.startsWith("{") -> JSONObject(trimmed).settingsArrayOrThrow()
                        else ->
                                throw IOException(
                                        "Unexpected settings response: ${trimmed.toResponsePreview()}"
                                )
                    }
                } catch (e: JSONException) {
                    throw IOException(
                            "Invalid settings JSON: ${trimmed.toResponsePreview()}",
                            e
                    )
                }

        return (0 until arr.length()).mapNotNull { index ->
            val obj = arr.optJSONObject(index) ?: return@mapNotNull null
            DeviceSetting.fromJson(obj)
        }
    }

    private fun JSONObject.settingsArrayOrThrow(): JSONArray {
        val arrayKeys = listOf("settings", "items", "data", "list", "entries", "results")
        for (key in arrayKeys) {
            val arr = optJSONArray(key)
            if (arr != null) return arr
        }

        val messageKeys = listOf("error", "message", "msg", "detail")
        for (key in messageKeys) {
            val message = optString(key, "")
            if (message.isNotBlank()) {
                throw IOException("Device returned: ${message.toResponsePreview()}")
            }
        }

        throw IOException("Unexpected settings response: ${toString().toResponsePreview()}")
    }

    private fun String.toResponsePreview(): String {
        val singleLine = replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= RESPONSE_PREVIEW_LENGTH) {
            singleLine
        } else {
            "${singleLine.take(RESPONSE_PREVIEW_LENGTH)}..."
        }
    }
}
