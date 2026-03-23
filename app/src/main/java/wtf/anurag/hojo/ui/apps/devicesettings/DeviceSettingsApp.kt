package wtf.anurag.hojo.ui.apps.devicesettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import wtf.anurag.hojo.data.model.DeviceSetting
import wtf.anurag.hojo.ui.viewmodels.DeviceSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsApp(
        onBack: () -> Unit,
        viewModel: DeviceSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val pendingChanges by viewModel.pendingChanges.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val hasPendingChanges = pendingChanges.isNotEmpty()

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearError()
        }
    }

    Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                        title = {
                            Text(if (hasPendingChanges) "Device Settings*" else "Device Settings")
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(
                                    onClick = { viewModel.saveSettings() },
                                    enabled = hasPendingChanges && !isSaving
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                            modifier = androidx.compose.ui.Modifier.padding(8.dp),
                                            strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                            Icons.Default.Save,
                                            contentDescription = "Save",
                                            tint = if (hasPendingChanges)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
            containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val grouped = settings.groupBy { it.category }
            LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)
            ) {
                grouped.forEach { (category, items) ->
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                                text = category,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                        )
                        HorizontalDivider()
                    }
                    items(items, key = { it.key }) { setting ->
                        SettingRow(
                                setting = setting,
                                pendingValue = pendingChanges[setting.key],
                                onStage = { key, value -> viewModel.stageSetting(key, value) }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SettingRow(
        setting: DeviceSetting,
        pendingValue: Any?,
        onStage: (String, Any) -> Unit
) {
    when (setting) {
        is DeviceSetting.Toggle -> ToggleRow(setting, pendingValue as? Boolean, onStage)
        is DeviceSetting.Enum -> EnumRow(setting, pendingValue as? Int, onStage)
        is DeviceSetting.Value -> ValueRow(setting, pendingValue as? Int, onStage)
        is DeviceSetting.Text -> TextRow(setting, pendingValue as? String, onStage)
    }
}

@Composable
private fun ToggleRow(
        setting: DeviceSetting.Toggle,
        pendingValue: Boolean?,
        onStage: (String, Any) -> Unit
) {
    val current = pendingValue ?: setting.value
    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = setting.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
        Switch(
                checked = current,
                onCheckedChange = { onStage(setting.key, it) }
        )
    }
}

@Composable
private fun EnumRow(
        setting: DeviceSetting.Enum,
        pendingValue: Int?,
        onStage: (String, Any) -> Unit
) {
    val current = pendingValue ?: setting.value
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(setting.name) },
                text = {
                    Column(modifier = Modifier.selectableGroup()) {
                        setting.options.forEachIndexed { index, option ->
                            Row(
                                    modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .selectable(
                                                    selected = index == current,
                                                    onClick = {
                                                        onStage(setting.key, index)
                                                        showDialog = false
                                                    },
                                                    role = Role.RadioButton
                                            )
                                            .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = index == current, onClick = null)
                                Text(
                                        text = option,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
        )
    }

    Column(
            modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = false, onClick = { showDialog = true })
                    .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        Text(text = setting.name, style = MaterialTheme.typography.bodyLarge)
        Text(
                text = setting.options.getOrElse(current) { current.toString() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ValueRow(
        setting: DeviceSetting.Value,
        pendingValue: Int?,
        onStage: (String, Any) -> Unit
) {
    val current = pendingValue ?: setting.value
    var sliderPosition by remember(setting.key, current) { mutableStateOf(current.toFloat()) }

    val steps = if (setting.step > 0) ((setting.max - setting.min) / setting.step) - 1 else 0

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp)) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = setting.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                    text = sliderPosition.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { onStage(setting.key, sliderPosition.toInt()) },
                valueRange = setting.min.toFloat()..setting.max.toFloat(),
                steps = steps.coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth()
        )
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                    text = setting.min.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                    text = setting.max.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextRow(
        setting: DeviceSetting.Text,
        pendingValue: String?,
        onStage: (String, Any) -> Unit
) {
    var text by rememberSaveable(setting.key) { mutableStateOf(pendingValue ?: setting.value) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
        OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(setting.name) },
                visualTransformation = if (setting.isPassword) PasswordVisualTransformation()
                else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onStage(setting.key, text) }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
        )
    }
}
