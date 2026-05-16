package wtf.anurag.hojo.ui.apps.converter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import wtf.anurag.hojo.ui.i18n.LocalAppStrings
import wtf.anurag.hojo.ui.viewmodels.ConnectivityViewModel

private val CDN_FONTS = listOf(
    "Literata", "Noto Sans CJK TC", "Lora", "Merriweather", "Open Sans",
    "Source Serif 4", "Noto Sans", "Noto Serif", "Roboto", "EB Garamond", "Crimson Pro"
)

private val WORD_SPACING_OPTIONS = listOf(50, 75, 100, 125, 150, 200)

private data class DropdownOption<T>(val value: T, val label: String)

private fun conversionPercent(progress: Int, total: Int): Int {
    if (total <= 0) return progress.coerceIn(0, 100)
    return ((progress.coerceAtLeast(0).toFloat() / total) * 100).toInt().coerceIn(0, 100)
}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Suppress("DEPRECATION")
@Composable
fun ConverterApp(onBack: () -> Unit, connectivityViewModel: ConnectivityViewModel = viewModel()) {
    val viewModel: ConverterViewModel = hiltViewModel()

    val status by viewModel.status.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val deviceBaseUrl by connectivityViewModel.deviceBaseUrl.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    val text = LocalAppStrings.current

    if (status is ConverterStatus.Preview) {
        val previewStatus = status as ConverterStatus.Preview
        XtcPreviewScreen(
            file = previewStatus.outputFile,
            onBack = {
                viewModel.reset()
                onBack()
            },
            onUpload = {
                viewModel.uploadToEpaper(
                    previewStatus.outputFile,
                    deviceBaseUrl,
                    text.uploadQueuedInTasks,
                    text.deviceNotConnected,
                    text.uploadSuccessful,
                    text.failed,
                    isConnected
                )
            },
            onSaveToDownloads = { viewModel.saveToDownloads(previewStatus.outputFile) },
            isSaved = previewStatus.isSaved,
            uploadEnabled = isConnected,
            uploadState = uploadState
        )
        return
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.selectFile(it, text.invalidEpubFile) } }
    )
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importFont(it) } }
    )

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text.epubConverter) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = text.back)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selectedFile == null) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(text.selectEpubFile) }
                } else {
                    Text(
                        text = "${text.selected}: ${selectedFileName.orEmpty()}",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    val availableFonts by viewModel.availableFonts.collectAsState()
                    SettingsSection(
                        settings = settings,
                        availableFonts = availableFonts,
                        onUpdate = { viewModel.updateSettings(it) },
                        onImportFont = {
                            fontPickerLauncher.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "font/collection",
                                    "application/x-font-ttf",
                                    "application/x-font-otf",
                                    "application/x-font-ttc"
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.startConversion() },
                        enabled = status is ConverterStatus.Idle,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(text.convertToXtc) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (val s = status) {
                    is ConverterStatus.ReadingFile ->
                        Text(text.readingFile, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is ConverterStatus.Converting -> {
                        val percent = conversionPercent(s.progress, s.total)
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text.convertingPage(percent, 100),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    is ConverterStatus.Uploading -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(text.uploadingToDevice, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    is ConverterStatus.UploadSuccess -> {
                        Text(text.uploadSuccessful, color = Color.Green, fontWeight = FontWeight.Bold)
                        Button(onClick = { viewModel.reset() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(text.convertAnother)
                        }
                    }
                    is ConverterStatus.Error ->
                        Text("${text.error}: ${s.message}", color = Color.Red)
                    else -> {}
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsSection(
    settings: ConverterSettings,
    availableFonts: List<java.io.File>,
    onUpdate: (ConverterSettings) -> Unit,
    onImportFont: () -> Unit
) {
    val text = LocalAppStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Device ──────────────────────────────────────────────────────────────
        SectionHeader(text.converterDeviceSection)

        SettingsRow(text.converterOrientation) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°").forEach { (deg, label) ->
                    FilterChip(
                        selected = settings.orientation == deg,
                        onClick = { onUpdate(settings.copy(orientation = deg)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Text ────────────────────────────────────────────────────────────────
        SectionHeader(text.converterTextSection)

        // Font Face (CDN)
        DropdownSetting(
            label = text.converterFontFace,
            current = settings.fontFace,
            options = CDN_FONTS.map { DropdownOption(it, it) },
            onSelect = { onUpdate(settings.copy(fontFace = it, fontFamily = "")) },
            displayOverride = if (settings.fontFamily.isEmpty()) null else text.converterCustomFont(settings.fontFamily.split("/").last())
        )

        // Custom font
        if (settings.fontFamily.isNotEmpty()) {
            Text(
                text.converterCustomFont(settings.fontFamily.split("/").last()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        OutlinedButton(
            onClick = onImportFont,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) { Text(text.importCustomFont) }

        if (availableFonts.isNotEmpty()) {
            DropdownSetting(
                label = text.importCustomFont,
                current = settings.fontFamily,
                options = availableFonts.map { DropdownOption(it.absolutePath, it.name) },
                onSelect = { onUpdate(settings.copy(fontFamily = it)) }
            )
        }

        // Text Alignment
        DropdownSetting(
            label = text.converterTextAlignment,
            current = settings.textAlign,
            options = listOf(
                DropdownOption(-1, text.converterDefault),
                DropdownOption(0, text.converterLeft),
                DropdownOption(1, text.converterRight),
                DropdownOption(2, text.converterCenter),
                DropdownOption(3, text.converterJustify)
            ),
            onSelect = { onUpdate(settings.copy(textAlign = it)) }
        )

        // Word Spacing
        DropdownSetting(
            label = text.converterWordSpacing,
            current = settings.wordSpacing,
            options = WORD_SPACING_OPTIONS.map { DropdownOption(it, "$it%") },
            onSelect = { onUpdate(settings.copy(wordSpacing = it)) }
        )

        // Hyphenation
        DropdownSetting(
            label = text.converterHyphenation,
            current = settings.hyphenation,
            options = listOf(
                DropdownOption(0, text.converterHyphenationOff),
                DropdownOption(1, text.converterHyphenationAlgorithmic),
                DropdownOption(2, text.converterHyphenationDictionary)
            ),
            onSelect = { onUpdate(settings.copy(hyphenation = it)) }
        )

        if (settings.hyphenation > 0) {
            SettingsRow(text.converterHyphenationLanguage) {
                OutlinedTextField(
                    value = settings.hyphenationLang,
                    onValueChange = { onUpdate(settings.copy(hyphenationLang = it)) },
                    singleLine = true,
                    placeholder = { Text(text.auto) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Font Weight
        SliderSetting(
            label = text.converterFontWeight,
            value = settings.fontWeight,
            displayValue = settings.fontWeight.toString(),
            valueRange = 100f..900f,
            steps = 7,
            onValueChange = { onUpdate(settings.copy(fontWeight = (it / 100).toInt() * 100)) }
        )

        // Line Height
        SliderSetting(
            label = text.converterLineHeight,
            value = settings.lineHeight,
            displayValue = "${settings.lineHeight}%",
            valueRange = 80f..200f,
            steps = 11,
            onValueChange = { onUpdate(settings.copy(lineHeight = it.toInt())) }
        )

        // Font Size
        SliderSetting(
            label = text.fontSize,
            value = settings.fontSize,
            displayValue = "${settings.fontSize}px",
            valueRange = 14f..48f,
            steps = 33,
            onValueChange = { onUpdate(settings.copy(fontSize = it.toInt())) }
        )

        // Margin
        SliderSetting(
            label = text.converterMargin,
            value = settings.margin,
            displayValue = "${settings.margin}px",
            valueRange = 0f..50f,
            steps = 49,
            onValueChange = { onUpdate(settings.copy(margin = it.toInt())) }
        )

        // Ignore Doc Margins
        CheckboxSetting(
            label = text.converterIgnoreDocumentMargins,
            checked = settings.ignoreDocMargins,
            onCheckedChange = { onUpdate(settings.copy(ignoreDocMargins = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Image ───────────────────────────────────────────────────────────────
        SectionHeader(text.converterImageSection)

        DropdownSetting(
            label = text.converterColorMode,
            current = settings.colorMode,
            options = listOf(
                DropdownOption(ConverterSettings.ColorMode.GRAYSCALE_4, text.converterGrayscale4),
                DropdownOption(ConverterSettings.ColorMode.MONOCHROME, text.converterMonochrome1)
            ),
            onSelect = { onUpdate(settings.copy(colorMode = it)) }
        )

        CheckboxSetting(
            label = text.converterEnableDithering,
            checked = settings.enableDithering,
            onCheckedChange = { onUpdate(settings.copy(enableDithering = it)) }
        )

        if (settings.enableDithering) {
            SliderSetting(
                label = text.converterDitherStrength,
                value = settings.ditherStrength,
                displayValue = "${settings.ditherStrength}%",
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = { onUpdate(settings.copy(ditherStrength = it.toInt())) }
            )
        }

        CheckboxSetting(
            label = text.converterDarkModeInvert,
            checked = settings.enableNegative,
            onCheckedChange = { onUpdate(settings.copy(enableNegative = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Progress Bar ────────────────────────────────────────────────────────
        SectionHeader(text.converterProgressBarSection)

        CheckboxSetting(
            label = text.converterEnableProgressBar,
            checked = settings.enableProgressBar,
            onCheckedChange = { onUpdate(settings.copy(enableProgressBar = it)) }
        )

        if (settings.enableProgressBar) {
            DropdownSetting(
                label = text.converterPosition,
                current = settings.progressPosition,
                options = listOf(
                    DropdownOption("top", text.converterTop),
                    DropdownOption("bottom", text.converterBottom)
                ),
                onSelect = { onUpdate(settings.copy(progressPosition = it)) }
            )

            CheckboxSetting(text.converterShowProgressLine, settings.showProgressLine) { onUpdate(settings.copy(showProgressLine = it)) }
            CheckboxSetting(text.converterShowChapterMarks, settings.showChapterMarks) { onUpdate(settings.copy(showChapterMarks = it)) }
            CheckboxSetting(text.converterShowChapterProgress, settings.showChapterProgress) { onUpdate(settings.copy(showChapterProgress = it)) }
            CheckboxSetting(text.converterFullWidth, settings.progressFullWidth) { onUpdate(settings.copy(progressFullWidth = it)) }
            CheckboxSetting(text.converterShowPageInfo, settings.showPageInfo) { onUpdate(settings.copy(showPageInfo = it)) }
            CheckboxSetting(text.converterShowBookPercent, settings.showBookPercent) { onUpdate(settings.copy(showBookPercent = it)) }
            CheckboxSetting(text.converterShowChapterPage, settings.showChapterPage) { onUpdate(settings.copy(showChapterPage = it)) }
            CheckboxSetting(text.converterShowChapterPercent, settings.showChapterPercent) { onUpdate(settings.copy(showChapterPercent = it)) }

            SliderSetting(
                label = text.converterBarFontSize,
                value = settings.progressFontSize,
                displayValue = "${settings.progressFontSize}px",
                valueRange = 10f..20f,
                steps = 9,
                onValueChange = { onUpdate(settings.copy(progressFontSize = it.toInt())) }
            )

            SliderSetting(
                label = text.converterEdgeMargin,
                value = settings.progressEdgeMargin,
                displayValue = "${settings.progressEdgeMargin}px",
                valueRange = 0f..30f,
                steps = 29,
                onValueChange = { onUpdate(settings.copy(progressEdgeMargin = it.toInt())) }
            )

            SliderSetting(
                label = text.converterSideMargin,
                value = settings.progressSideMargin,
                displayValue = "${settings.progressSideMargin}px",
                valueRange = 0f..30f,
                steps = 29,
                onValueChange = { onUpdate(settings.copy(progressSideMargin = it.toInt())) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSetting(
    label: String,
    current: T,
    options: List<DropdownOption<T>>,
    onSelect: (T) -> Unit,
    displayOverride: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = displayOverride ?: options.firstOrNull { it.value == current }?.label.orEmpty()
    SettingsRow(label) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onSelect(option.value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    displayValue: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(displayValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun CheckboxSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

