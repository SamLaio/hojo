package wtf.anurag.hojo.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanguageMode {
    ZH_TW,
    EN
}

@Singleton
class LanguageRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences =
            context.getSharedPreferences("hojo_prefs", Context.MODE_PRIVATE)

    private val _languageMode = MutableStateFlow(getStoredLanguageMode())
    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    fun setLanguageMode(mode: LanguageMode) {
        prefs.edit().putString("language_mode", mode.name).apply()
        _languageMode.value = mode
    }

    private fun getStoredLanguageMode(): LanguageMode {
        val modeName =
                prefs.getString("language_mode", LanguageMode.ZH_TW.name)
                        ?: LanguageMode.ZH_TW.name
        return try {
            LanguageMode.valueOf(modeName)
        } catch (e: IllegalArgumentException) {
            LanguageMode.ZH_TW
        }
    }
}
