package wtf.anurag.hojo.ui.apps.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import wtf.anurag.hojo.data.repository.LanguageMode
import wtf.anurag.hojo.data.repository.ThemeMode
import wtf.anurag.hojo.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsApp(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val languageMode by viewModel.languageMode.collectAsState()
    val copy = remember(languageMode) { settingsCopy(languageMode) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: ""

    if (showThemeDialog) {
        AlertDialog(
                onDismissRequest = { showThemeDialog = false },
                title = { Text(copy.selectTheme) },
                text = {
                    Column(modifier = Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .height(56.dp)
                                                    .selectable(
                                                            selected = (mode == themeMode),
                                                            onClick = {
                                                                viewModel.setTheme(mode)
                                                                showThemeDialog = false
                                                            },
                                                            role = Role.RadioButton
                                                    )
                                                    .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                        selected = (mode == themeMode),
                                        onClick = null // null recommended for accessibility with
                                        // selectable
                                        )
                                Text(
                                        text =
                                                when (mode) {
                                                    ThemeMode.SYSTEM -> copy.themeSystem
                                                    ThemeMode.LIGHT -> copy.themeLight
                                                    ThemeMode.DARK -> copy.themeDark
                                                },
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemeDialog = false }) { Text(copy.cancel) }
                }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(copy.selectLanguage) },
                text = {
                    Column(modifier = Modifier.selectableGroup()) {
                        LanguageMode.entries.forEach { mode ->
                            Row(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .height(56.dp)
                                                    .selectable(
                                                            selected = (mode == languageMode),
                                                            onClick = {
                                                                viewModel.setLanguage(mode)
                                                                showLanguageDialog = false
                                                            },
                                                            role = Role.RadioButton
                                                    )
                                                    .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = (mode == languageMode), onClick = null)
                                Text(
                                        text = languageLabel(mode),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) { Text(copy.cancel) }
                }
        )
    }

    Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                        title = { Text(copy.settings) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = copy.back
                                )
                            }
                        },
                        colors =
                                TopAppBarDefaults.centerAlignedTopAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface
                                )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            // Theme Section (Placeholder for now)
            SettingsSection(title = copy.appearance) {
                SettingsItem(
                        icon = Icons.Default.Palette,
                        title = copy.theme,
                        subtitle =
                                when (themeMode) {
                                    ThemeMode.SYSTEM -> copy.themeSystem
                                    ThemeMode.LIGHT -> copy.themeLight
                                    ThemeMode.DARK -> copy.themeDark
                                },
                        onClick = { showThemeDialog = true }
                )
                SettingsItem(
                        icon = Icons.Default.Language,
                        title = copy.language,
                        subtitle = languageLabel(languageMode),
                        onClick = { showLanguageDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About Section
            SettingsSection(title = copy.about) {
                SettingsItem(
                        icon = Icons.Default.Info,
                        title = copy.appVersion,
                        subtitle = versionName,
                        onClick = {}
                )
                SettingsItem(
                        icon = Icons.Default.Link,
                        title = copy.githubRepository,
                        subtitle = copy.viewSourceCode,
                        onClick = {
                            val intent =
                                    Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/meta-boy/hojo")
                                    )
                            context.startActivity(intent)
                        }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun SettingsItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        subtitle: String,
        onClick: () -> Unit
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
        )
        Column {
            Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class SettingsCopy(
        val settings: String,
        val back: String,
        val appearance: String,
        val theme: String,
        val selectTheme: String,
        val themeSystem: String,
        val themeLight: String,
        val themeDark: String,
        val language: String,
        val selectLanguage: String,
        val about: String,
        val appVersion: String,
        val githubRepository: String,
        val viewSourceCode: String,
        val cancel: String
)

private fun settingsCopy(languageMode: LanguageMode): SettingsCopy {
    return when (languageMode) {
        LanguageMode.ZH_TW ->
                SettingsCopy(
                        settings = "設定",
                        back = "返回",
                        appearance = "外觀",
                        theme = "主題",
                        selectTheme = "選擇主題",
                        themeSystem = "跟隨系統",
                        themeLight = "淺色",
                        themeDark = "深色",
                        language = "語言",
                        selectLanguage = "選擇語言",
                        about = "關於",
                        appVersion = "應用程式版本",
                        githubRepository = "GitHub 原始碼",
                        viewSourceCode = "查看原始碼",
                        cancel = "取消"
                )
        LanguageMode.EN ->
                SettingsCopy(
                        settings = "Settings",
                        back = "Back",
                        appearance = "Appearance",
                        theme = "Theme",
                        selectTheme = "Select Theme",
                        themeSystem = "System Default",
                        themeLight = "Light",
                        themeDark = "Dark",
                        language = "Language",
                        selectLanguage = "Select Language",
                        about = "About",
                        appVersion = "App Version",
                        githubRepository = "GitHub Repository",
                        viewSourceCode = "View source code",
                        cancel = "Cancel"
                )
    }
}

private fun languageLabel(languageMode: LanguageMode): String {
    return when (languageMode) {
        LanguageMode.ZH_TW -> "正體中文"
        LanguageMode.EN -> "English"
    }
}
