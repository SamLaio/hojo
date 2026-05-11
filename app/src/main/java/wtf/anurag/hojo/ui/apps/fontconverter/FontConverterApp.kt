package wtf.anurag.hojo.ui.apps.fontconverter

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import wtf.anurag.hojo.ui.i18n.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun FontConverterApp(onBack: () -> Unit) {
    val viewModel: FontConverterViewModel = hiltViewModel()
    val status by viewModel.status.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val text = LocalAppStrings.current

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
                                Icon(Icons.Filled.ArrowBack, contentDescription = text.back)
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
                        enabled = status !is FontConverterStatus.Converting &&
                                status !is FontConverterStatus.ReadingFile,
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                )
                ) {
                    Text(text.selectFontFile)
                }

                selectedFileName?.let {
                    Text(
                            text = "${text.selected}: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                    )
                }

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
                    is FontConverterStatus.Saved -> {
                        Text(
                                text = text.fontConvertSaved(current.fileName, current.location),
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                        )
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
        }
    }
}
