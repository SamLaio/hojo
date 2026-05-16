package wtf.anurag.hojo.ui.apps.fontconverter

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import wtf.anurag.hojo.ui.apps.converter.ConverterUploadPhase
import wtf.anurag.hojo.ui.apps.converter.ConverterUploadState
import wtf.anurag.hojo.ui.i18n.AppStrings
import wtf.anurag.hojo.ui.i18n.LocalAppStrings
import wtf.anurag.hojo.ui.viewmodels.ConnectivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun FontConverterApp(onBack: () -> Unit) {
    val viewModel: FontConverterViewModel = hiltViewModel()
    val connectivityViewModel: ConnectivityViewModel = hiltViewModel()
    val status by viewModel.status.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedCharsetIds by viewModel.selectedCharsetIds.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    val text = LocalAppStrings.current
    val isBusy = status is FontConverterStatus.Converting || status is FontConverterStatus.ReadingFile
    var showCharsetDialog by remember { mutableStateOf(false) }

    val filePickerLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                    onResult = { uri -> uri?.let { viewModel.selectFont(it) } }
            )

    Scaffold(
            topBar = {
                LargeTopAppBar(
                        title = { Text(text.fontConverter) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = text.back)
                            }
                        },
                        colors =
                                TopAppBarDefaults.largeTopAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                                )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                        text = text.fontConverterDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(
                            onClick = { viewModel.decreaseFontSize() },
                            enabled = status !is FontConverterStatus.Converting &&
                                    status !is FontConverterStatus.ReadingFile
                    ) {
                        Text("-")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                            value = fontSize.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { viewModel.updateFontSize(it) }
                            },
                            enabled = status !is FontConverterStatus.Converting &&
                                    status !is FontConverterStatus.ReadingFile,
                            singleLine = true,
                            label = { Text(text.fontSize) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(132.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                            onClick = { viewModel.increaseFontSize() },
                            enabled = status !is FontConverterStatus.Converting &&
                                    status !is FontConverterStatus.ReadingFile
                    ) {
                        Text("+")
                    }
                }

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                            onClick = {
                                filePickerLauncher.launch(
                                        arrayOf(
                                                "font/ttf",
                                                "font/otf",
                                                "font/collection",
                                                "application/x-font-ttf",
                                                "application/x-font-otf",
                                                "application/octet-stream"
                                        )
                                )
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors =
                                    ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                    )
                    ) {
                        Text(text.selectFontFile)
                    }

                    OutlinedButton(
                            onClick = { showCharsetDialog = true },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                    ) {
                        Text("${text.selectCharset} (${selectedCharsetIds.size})")
                    }
                }

                Button(
                        onClick = { viewModel.startConversion() },
                        enabled = selectedFileName != null && !isBusy,
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text.startFontConversion)
                }

                selectedFileName?.let {
                    Text(
                            text = "${text.selected}: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                        text =
                                FontCharacterSets.selectedLabel(
                                        selectedCharsetIds,
                                        labelForId = text::fontCharsetLabel
                                ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (val current = status) {
                    FontConverterStatus.Idle -> Unit
                    FontConverterStatus.ReadingFile -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(text.readingFile)
                    }
                    is FontConverterStatus.Converting -> {
                        LinearProgressIndicator(
                                progress = {
                                    if (current.total > 0) {
                                        current.current.toFloat() / current.total.toFloat()
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                                text = text.convertingFont(current.current, current.total),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is FontConverterStatus.Converted -> {
                        Text(
                                text = if (current.isSaved && current.savedLocation != null) {
                                    text.fontConvertSaved(current.outputFile.name, current.savedLocation)
                                } else {
                                    "${text.completed}: ${current.outputFile.name}"
                                },
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                        )
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                    onClick = { viewModel.saveToDownloads(current.outputFile) },
                                    enabled = !current.isSaved,
                                    modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                        Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (current.isSaved) text.saved else text.save)
                            }

                            Button(
                                    onClick = {
                                        viewModel.uploadToEpaper(
                                                current.outputFile,
                                                text.uploadQueuedInTasks,
                                                text.deviceNotConnected,
                                                text.uploadSuccessful,
                                                text.failed,
                                                isConnected
                                        )
                                    },
                                    enabled = isConnected && !uploadState.isInProgress,
                                    modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                        Icons.Filled.Upload,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                        when {
                                            uploadState.isInProgress -> text.uploading
                                            isConnected -> text.upload
                                            else -> text.uploadRequiresConnection
                                        }
                                )
                            }
                        }
                        FontUploadStatusMessage(uploadState, text)
                        OutlinedButton(onClick = { viewModel.reset() }) {
                            Text(text.convertAnother)
                        }
                    }
                    is FontConverterStatus.Error -> {
                        Text("${text.error}: ${current.message}", color = Color.Red)
                        OutlinedButton(onClick = { viewModel.reset() }) {
                            Text(text.retry)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text = text.fontConverterOutputHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showCharsetDialog) {
                FontCharsetDialog(
                        selectedIds = selectedCharsetIds,
                        labelForId = text::fontCharsetLabel,
                        title = text.selectCharset,
                        confirmText = text.save,
                        cancelText = text.cancel,
                        onDismiss = { showCharsetDialog = false },
                        onApply = {
                            viewModel.updateSelectedCharsets(it)
                            showCharsetDialog = false
                        }
                )
            }
        }
    }
}

@Composable
private fun FontCharsetDialog(
        selectedIds: Set<String>,
        labelForId: (String) -> String,
        title: String,
        confirmText: String,
        cancelText: String,
        onDismiss: () -> Unit,
        onApply: (Set<String>) -> Unit
) {
    var pending by remember(selectedIds) { mutableStateOf(selectedIds) }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(
                        modifier =
                                Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                ) {
                    FontCharacterSets.options.forEach { option ->
                        val checked = option.id in pending
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        pending =
                                                if (isChecked) {
                                                    pending + option.id
                                                } else {
                                                    pending - option.id
                                                }
                                    }
                            )
                            Text(labelForId(option.id))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onApply(pending) }) {
                    Text(confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(cancelText)
                }
            }
    )
}

private fun AppStrings.fontCharsetLabel(id: String): String {
    return when (id) {
        "tc_common" -> fontCharsetTcCommon
        "tc_less_common" -> fontCharsetTcLessCommon
        "japanese" -> fontCharsetJapanese
        "sc" -> fontCharsetSimplifiedChinese
        "bopomofo" -> fontCharsetBopomofo
        "latin" -> fontCharsetEnglish
        "latin_extended" -> fontCharsetLatin
        "hangul" -> fontCharsetKorean
        "greek_cyrillic" -> fontCharsetGreekCyrillic
        "symbols" -> fontCharsetSymbolsMath
        else -> id
    }
}

@Composable
private fun FontUploadStatusMessage(uploadState: ConverterUploadState, text: AppStrings) {
    val message =
            when (uploadState.phase) {
                ConverterUploadPhase.IDLE -> null
                ConverterUploadPhase.QUEUED -> text.uploadQueuedInTasks
                ConverterUploadPhase.UPLOADING -> text.uploading
                ConverterUploadPhase.COMPLETED -> text.uploadSuccessful
                ConverterUploadPhase.FAILED ->
                        "${text.failed}: ${localizedFontUploadError(uploadState.error, text)}"
                ConverterUploadPhase.CANCELLED -> text.cancelled
            }

    if (message == null) return

    if (uploadState.phase == ConverterUploadPhase.UPLOADING) {
        LinearProgressIndicator(
                progress = { uploadState.progress },
                modifier = Modifier.fillMaxWidth()
        )
    }

    val color =
            when (uploadState.phase) {
                ConverterUploadPhase.FAILED -> MaterialTheme.colorScheme.error
                ConverterUploadPhase.COMPLETED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
    Text(
            text = message,
            color = color,
            style = MaterialTheme.typography.bodySmall
    )
}

private fun localizedFontUploadError(error: String?, text: AppStrings): String {
    return when (error) {
        "Device not connected" -> text.deviceNotConnected
        null -> ""
        else -> error
    }
}
