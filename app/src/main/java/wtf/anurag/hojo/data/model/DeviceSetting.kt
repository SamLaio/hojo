package wtf.anurag.hojo.data.model

private val PASSWORD_KEYS = setOf("koPassword", "opdsPassword")

sealed class DeviceSetting {
    abstract val key: String
    abstract val name: String
    abstract val category: String

    data class Toggle(
            override val key: String,
            override val name: String,
            override val category: String,
            val value: Boolean
    ) : DeviceSetting()

    data class Enum(
            override val key: String,
            override val name: String,
            override val category: String,
            val value: Int,
            val options: List<String>
    ) : DeviceSetting()

    data class Value(
            override val key: String,
            override val name: String,
            override val category: String,
            val value: Int,
            val min: Int,
            val max: Int,
            val step: Int
    ) : DeviceSetting()

    data class Text(
            override val key: String,
            override val name: String,
            override val category: String,
            val value: String,
            val isPassword: Boolean = false
    ) : DeviceSetting()

    companion object {
        fun fromJson(obj: org.json.JSONObject): DeviceSetting? {
            val key = obj.optString("key") ?: return null
            val name = obj.optString("name", key)
            val category = obj.optString("category", "Other")
            return when (obj.optString("type")) {
                "toggle" -> Toggle(key, name, category, obj.optInt("value", 0) != 0)
                "enum" -> {
                    val optionsArr = obj.optJSONArray("options") ?: return null
                    val options = (0 until optionsArr.length()).map { optionsArr.getString(it) }
                    Enum(key, name, category, obj.optInt("value", 0), options)
                }
                "value" -> Value(
                        key, name, category,
                        obj.optInt("value", 0),
                        obj.optInt("min", 0),
                        obj.optInt("max", 100),
                        obj.optInt("step", 1)
                )
                "string" -> Text(
                        key, name, category,
                        obj.optString("value", ""),
                        isPassword = key in PASSWORD_KEYS
                )
                else -> null
            }
        }
    }
}
