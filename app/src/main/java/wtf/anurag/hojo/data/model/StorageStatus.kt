package wtf.anurag.hojo.data.model

data class StorageStatus(
        val totalBytes: Long? = null,
        val usedBytes: Long? = null,
        val version: String? = null,
        val ip: String? = null,
        val mode: String? = null,
        val rssi: Int? = null,
        val freeHeap: Long? = null,
        val uptime: Long? = null
)
