package id.yumtaufikhidayat.jetcalllab.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun PermissionGateDialog(
    micGranted: Boolean,
    notifGranted: Boolean,
    needNotifPermission: Boolean,
    notificationsEnabled: Boolean,
    runtimeNotifDenied: Boolean, // <--- Tambahkan parameter ini
    isCall: Boolean,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onOpenNotifSettings: () -> Unit,
) {
    val missingMic = !micGranted
    val missingNotifPermission = needNotifPermission && !notifGranted

    val notifBlocked = (needNotifPermission && notifGranted && !notificationsEnabled) || (missingNotifPermission && runtimeNotifDenied)

    val header = if (isCall) "Start call" else "Answer call"

    val message = buildString {
        append("$header needs:\n\n")
        if (missingMic) append("• Microphone access\n")
        if (missingNotifPermission) append("• Notification permission\n")
        if (notifBlocked && !missingNotifPermission) append("• Notifications enabled in system settings\n")
    }

    val primaryText = when {
        missingMic -> "Allow microphone"
        notifBlocked -> "Open notification settings"
        missingNotifPermission -> "Allow notifications"
        else -> if (isCall) "Start" else "Answer"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission required") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = {
                if (notifBlocked) onOpenNotifSettings() else onPrimary()
            }) { Text(primaryText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}