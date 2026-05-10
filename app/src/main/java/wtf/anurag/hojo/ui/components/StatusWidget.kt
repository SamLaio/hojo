package wtf.anurag.hojo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import wtf.anurag.hojo.R
import wtf.anurag.hojo.data.model.StorageStatus
import wtf.anurag.hojo.ui.i18n.LocalAppStrings

data class StatusWidgetText(
    val deviceStatus: String = "Device Status",
    val online: String = "Online",
    val offline: String = "Offline",
    val connection: String = "Connection",
    val notConnected: String = "Not Connected",
    val connectedViaWifi: String = "Connected via WiFi",
    val connect: String = "Connect",
    val signal: String = "Signal",
    val excellent: String = "Excellent",
    val good: String = "Good",
    val fair: String = "Fair",
    val weak: String = "Weak",
    val unknown: String = "Unknown",
    val uptime: String = "Uptime",
    val firmware: String = "Firmware"
)

@Composable
fun StatusWidget(
    isConnected: Boolean,
    isConnecting: Boolean,
    storageStatus: StorageStatus?,
    onConnect: (Boolean) -> Unit,
    text: StatusWidgetText = StatusWidgetText()
) {
    val appText = LocalAppStrings.current
    val resolvedText =
        if (text == StatusWidgetText()) {
            StatusWidgetText(
                deviceStatus = appText.deviceStatus,
                online = appText.online,
                offline = appText.offline,
                connection = appText.connection,
                notConnected = appText.notConnected,
                connectedViaWifi = appText.connectedViaWifi,
                connect = appText.connect,
                signal = appText.signal,
                excellent = appText.excellent,
                good = appText.good,
                fair = appText.fair,
                weak = appText.weak,
                unknown = appText.unknown,
                uptime = appText.uptime,
                firmware = appText.firmware
            )
        } else {
            text
        }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier =
                                Modifier.size(28.dp)
                                        .clip(MaterialTheme.shapes.small)
                    )
                    Text(
                        text = resolvedText.deviceStatus,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (isConnected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isConnected) resolvedText.online else resolvedText.offline,
                        color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Connection / IP row
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(32.dp).padding(end = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector =
                        if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resolvedText.connection,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    val connectionDetail = when {
                        !isConnected -> resolvedText.notConnected
                        storageStatus?.ip != null -> {
                            val mode = storageStatus.mode ?: "STA"
                            "${storageStatus.ip} ($mode)"
                        }
                        else -> resolvedText.connectedViaWifi
                    }
                    Text(
                        text = connectionDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!isConnected) {
                    Box(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !isConnecting) { onConnect(false) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isConnecting) "..." else resolvedText.connect,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (isConnected && storageStatus != null) {
                Spacer(modifier = Modifier.height(16.dp))

                // Signal strength row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier.size(32.dp).padding(end = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedText.signal,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        val rssi = storageStatus.rssi
                        val rssiText = if (rssi != null) {
                            val quality = when {
                                rssi >= -50 -> resolvedText.excellent
                                rssi >= -65 -> resolvedText.good
                                rssi >= -75 -> resolvedText.fair
                                else -> resolvedText.weak
                            }
                            "$rssi dBm · $quality"
                        } else resolvedText.unknown
                        Text(
                            text = rssiText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Uptime row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier.size(32.dp).padding(end = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedText.uptime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Text(
                            text = storageStatus.uptime?.let { formatUptime(it) } ?: resolvedText.unknown,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Footer: firmware version
            if (storageStatus?.version != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = "${resolvedText.firmware}: ${storageStatus.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
