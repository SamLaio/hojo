package wtf.anurag.hojo.data.model

data class FileItem(
        val name: String,
        val isDirectory: Boolean = false,
        val isEpub: Boolean = false,
        val size: Long? = null
)
