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
                val url = "$baseUrl/api/settings"
                Log.d(TAG, "fetchSettings -> GET $url")

                val request = Request.Builder().url(url).build()
                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "fetchSettings -> response code: ${response.code}")
                    if (!response.isSuccessful) throw IOException("Settings fetch failed: ${response.code}")
                    val json = response.body?.string() ?: "[]"
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { DeviceSetting.fromJson(arr.getJSONObject(it)) }
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

                Log.d(TAG, "updateSettings -> POST $baseUrl/api/settings body=$body")

                val request = Request.Builder()
                        .url("$baseUrl/api/settings")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                deviceClientOrFallback().newCall(request).execute().use { response ->
                    Log.d(TAG, "updateSettings -> response code: ${response.code}")
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: response.code.toString()
                        throw IOException("Settings save failed: $err")
                    }
                }
            }
}
