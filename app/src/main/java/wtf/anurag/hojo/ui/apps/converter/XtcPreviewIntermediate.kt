package wtf.anurag.hojo.ui.apps.converter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import wtf.anurag.hojo.ui.i18n.LocalAppStrings

/**
 * Small intermediate preview shown before opening the full `XtcPreviewScreen`.
 * Shows file name/size and actions to open the full preview, upload or save.
 */
@Composable
fun XtcPreviewIntermediate(
    file: File,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onSaveToDownloads: () -> Unit,
    isSaved: Boolean,
    uploadEnabled: Boolean = true
) {
    val showFull = remember { mutableStateOf(false) }
    val text = LocalAppStrings.current

    if (showFull.value) {
        // Show the existing full preview screen; when it requests back, return to this intermediate view.
        XtcPreviewScreen(
            file = file,
            onBack = {
                // close full preview and return to intermediate
                showFull.value = false
            },
            onUpload = onUpload,
            onSaveToDownloads = onSaveToDownloads,
            isSaved = isSaved,
            uploadEnabled = uploadEnabled
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = text.previewFile)
        Text(text = "${text.name}: ${file.name}")
        Text(text = "${text.size}: ${file.length()} ${text.bytes}")

        // Primary actions grouped together
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { showFull.value = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(text.openPreview)
            }

            Button(
                onClick = onUpload,
                enabled = uploadEnabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (uploadEnabled) text.upload else text.uploadRequiresConnection)
            }
        }

        // Secondary actions grouped together
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSaveToDownloads,
                enabled = !isSaved,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isSaved) text.saved else text.saveToDownloads)
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(text.back)
            }
        }
    }
}
