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
import wtf.anurag.hojo.ui.viewmodels.ConnectivityViewModel

private val CDN_FONTS = listOf(
    "Literata", "Lora", "Merriweather", "Open Sans", "Source Serif 4",
    "Noto Sans", "Noto Serif", "Roboto", "EB Garamond", "Crimson Pro"
)

private val WORD_SPACING_OPTIONS = listOf(50, 75, 100, 125, 150, 200)

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Suppress("DEPRECATION")
@Composable
fun ConverterApp(onBack: () -> Unit, connectivityViewModel: ConnectivityViewModel = viewModel()) {
    val viewModel: ConverterViewModel = hiltViewModel()

    val status by viewModel.status.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val deviceBaseUrl by connectivityViewModel.deviceBaseUrl.collectAsState()

    if (status is ConverterStatus.Preview) {
        val previewStatus = status as ConverterStatus.Preview
        XtcPreviewScreen(
            file = previewStatus.outputFile,
            onBack = {
                viewModel.reset()
                onBack()
            },
            onUpload = { viewModel.uploadToEpaper(previewStatus.outputFile, deviceBaseUrl) },
            onSaveToDownloads = { viewModel.saveToDownloads(previewStatus.outputFile) },
            isSaved = previewStatus.isSaved
        )
        return
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.selectFile(it) } }
    )
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importFont(it) } }
    )

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("EPUB Converter") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                        onClick = { filePickerLauncher.launch(arrayOf("application/epub+zip")) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Select EPUB File") }
                } else {
                    Text(
                        text = "Selected: ${selectedFile?.path?.split("/")?.last()}",
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
                                arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf")
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.startConversion() },
                        enabled = status is ConverterStatus.Idle,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Convert to XTC") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (val s = status) {
                    is ConverterStatus.ReadingFile ->
                        Text("Reading file...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is ConverterStatus.Converting -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Converting: Page ${s.progress}" + if (s.total > 0) " / ${s.total}" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    is ConverterStatus.Uploading -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Uploading to e-paper...", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    is ConverterStatus.UploadSuccess -> {
                        Text("Upload Successful!", color = Color.Green, fontWeight = FontWeight.Bold)
                        Button(onClick = { viewModel.reset() }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Convert Another")
                        }
                    }
                    is ConverterStatus.Error ->
                        Text("Error: ${s.message}", color = Color.Red)
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
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Device ──────────────────────────────────────────────────────────────
        SectionHeader("Device")

        SettingsRow("Device") {
            SegmentedButtonRow {
                SegmentedButton(
                    selected = settings.deviceType == "x4",
                    onClick = { onUpdate(settings.copy(deviceType = "x4")) },
                    label = "X4 (480×800)"
                )
                SegmentedButton(
                    selected = settings.deviceType == "x3",
                    onClick = { onUpdate(settings.copy(deviceType = "x3")) },
                    label = "X3 (528×792)"
                )
            }
        }

        SettingsRow("Orientation") {
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
        SectionHeader("Text")

        // Font Face (CDN)
        DropdownSetting(
            label = "Font Face",
            current = if (settings.fontFamily.isEmpty()) settings.fontFace else "(Custom)",
            options = CDN_FONTS,
            onSelect = { onUpdate(settings.copy(fontFace = it, fontFamily = "")) }
        )

        // Custom font
        if (settings.fontFamily.isNotEmpty()) {
            Text(
                "Custom: ${settings.fontFamily.split("/").last()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        OutlinedButton(
            onClick = onImportFont,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) { Text("Import Custom Font") }

        // Text Alignment
        DropdownSetting(
            label = "Text Alignment",
            current = when (settings.textAlign) {
                -1 -> "Default"
                0 -> "Left"
                1 -> "Right"
                2 -> "Center"
                3 -> "Justify"
                else -> "Default"
            },
            options = listOf("Default", "Left", "Right", "Center", "Justify"),
            onSelect = {
                onUpdate(settings.copy(textAlign = when (it) {
                    "Left" -> 0; "Right" -> 1; "Center" -> 2; "Justify" -> 3; else -> -1
                }))
            }
        )

        // Word Spacing
        DropdownSetting(
            label = "Word Spacing",
            current = "${settings.wordSpacing}%",
            options = WORD_SPACING_OPTIONS.map { "$it%" },
            onSelect = { onUpdate(settings.copy(wordSpacing = it.removeSuffix("%").toInt())) }
        )

        // Hyphenation
        DropdownSetting(
            label = "Hyphenation",
            current = when (settings.hyphenation) { 0 -> "Off"; 1 -> "Algorithmic"; else -> "Dictionary" },
            options = listOf("Off", "Algorithmic", "Dictionary"),
            onSelect = { onUpdate(settings.copy(hyphenation = when (it) { "Off" -> 0; "Algorithmic" -> 1; else -> 2 })) }
        )

        if (settings.hyphenation > 0) {
            SettingsRow("Hyphenation Language") {
                OutlinedTextField(
                    value = settings.hyphenationLang,
                    onValueChange = { onUpdate(settings.copy(hyphenationLang = it)) },
                    singleLine = true,
                    placeholder = { Text("auto") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Font Weight
        SliderSetting(
            label = "Font Weight",
            value = settings.fontWeight,
            displayValue = settings.fontWeight.toString(),
            valueRange = 100f..900f,
            steps = 7,
            onValueChange = { onUpdate(settings.copy(fontWeight = (it / 100).toInt() * 100)) }
        )

        // Line Height
        SliderSetting(
            label = "Line Height",
            value = settings.lineHeight,
            displayValue = "${settings.lineHeight}%",
            valueRange = 80f..200f,
            steps = 11,
            onValueChange = { onUpdate(settings.copy(lineHeight = it.toInt())) }
        )

        // Font Size
        SliderSetting(
            label = "Font Size",
            value = settings.fontSize,
            displayValue = "${settings.fontSize}px",
            valueRange = 14f..48f,
            steps = 33,
            onValueChange = { onUpdate(settings.copy(fontSize = it.toInt())) }
        )

        // Margin
        SliderSetting(
            label = "Margin",
            value = settings.margin,
            displayValue = "${settings.margin}px",
            valueRange = 0f..50f,
            steps = 49,
            onValueChange = { onUpdate(settings.copy(margin = it.toInt())) }
        )

        // Ignore Doc Margins
        CheckboxSetting(
            label = "Ignore Document Margins",
            checked = settings.ignoreDocMargins,
            onCheckedChange = { onUpdate(settings.copy(ignoreDocMargins = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Image ───────────────────────────────────────────────────────────────
        SectionHeader("Image")

        DropdownSetting(
            label = "Color Mode",
            current = when (settings.colorMode) {
                ConverterSettings.ColorMode.GRAYSCALE_4 -> "Grayscale (4-level)"
                ConverterSettings.ColorMode.MONOCHROME -> "Monochrome (1-bit)"
            },
            options = listOf("Grayscale (4-level)", "Monochrome (1-bit)"),
            onSelect = {
                onUpdate(settings.copy(colorMode = if (it.startsWith("Mono")) ConverterSettings.ColorMode.MONOCHROME else ConverterSettings.ColorMode.GRAYSCALE_4))
            }
        )

        CheckboxSetting(
            label = "Enable Dithering",
            checked = settings.enableDithering,
            onCheckedChange = { onUpdate(settings.copy(enableDithering = it)) }
        )

        if (settings.enableDithering) {
            SliderSetting(
                label = "Dither Strength",
                value = settings.ditherStrength,
                displayValue = "${settings.ditherStrength}%",
                valueRange = 0f..100f,
                steps = 99,
                onValueChange = { onUpdate(settings.copy(ditherStrength = it.toInt())) }
            )
        }

        CheckboxSetting(
            label = "Dark Mode (Invert)",
            checked = settings.enableNegative,
            onCheckedChange = { onUpdate(settings.copy(enableNegative = it)) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Progress Bar ────────────────────────────────────────────────────────
        SectionHeader("Progress Bar")

        CheckboxSetting(
            label = "Enable Progress Bar",
            checked = settings.enableProgressBar,
            onCheckedChange = { onUpdate(settings.copy(enableProgressBar = it)) }
        )

        if (settings.enableProgressBar) {
            DropdownSetting(
                label = "Position",
                current = settings.progressPosition.replaceFirstChar { it.uppercase() },
                options = listOf("Top", "Bottom"),
                onSelect = { onUpdate(settings.copy(progressPosition = it.lowercase())) }
            )

            CheckboxSetting("Show Progress Line", settings.showProgressLine) { onUpdate(settings.copy(showProgressLine = it)) }
            CheckboxSetting("Show Chapter Marks", settings.showChapterMarks) { onUpdate(settings.copy(showChapterMarks = it)) }
            CheckboxSetting("Show Chapter Progress", settings.showChapterProgress) { onUpdate(settings.copy(showChapterProgress = it)) }
            CheckboxSetting("Full Width", settings.progressFullWidth) { onUpdate(settings.copy(progressFullWidth = it)) }
            CheckboxSetting("Show Page Info", settings.showPageInfo) { onUpdate(settings.copy(showPageInfo = it)) }
            CheckboxSetting("Show Book Percent", settings.showBookPercent) { onUpdate(settings.copy(showBookPercent = it)) }
            CheckboxSetting("Show Chapter Page", settings.showChapterPage) { onUpdate(settings.copy(showChapterPage = it)) }
            CheckboxSetting("Show Chapter Percent", settings.showChapterPercent) { onUpdate(settings.copy(showChapterPercent = it)) }

            SliderSetting(
                label = "Bar Font Size",
                value = settings.progressFontSize,
                displayValue = "${settings.progressFontSize}px",
                valueRange = 10f..20f,
                steps = 9,
                onValueChange = { onUpdate(settings.copy(progressFontSize = it.toInt())) }
            )

            SliderSetting(
                label = "Edge Margin",
                value = settings.progressEdgeMargin,
                displayValue = "${settings.progressEdgeMargin}px",
                valueRange = 0f..30f,
                steps = 29,
                onValueChange = { onUpdate(settings.copy(progressEdgeMargin = it.toInt())) }
            )

            SliderSetting(
                label = "Side Margin",
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
private fun DropdownSetting(
    label: String,
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(label) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false }
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

@Composable
private fun SegmentedButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun RowScope.SegmentedButton(selected: Boolean, onClick: () -> Unit, label: String) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).height(36.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.weight(1f).height(36.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

