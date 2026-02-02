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
    isCall: Boolean,
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val missingMic = !micGranted
    val missingNotif = needNotifPermission && (!notifGranted || !notificationsEnabled)
    val msg = "To ${if (isCall) "start" else "answer"}"

    val message = buildString {
        append("$msg a call, JetCallLab needs:\n\n")
        if (missingMic) append("• Microphone\n")
        if (missingNotif) append("• Notifications (for ongoing call)\n")
    }

    // CTA logic:
    // - If notif is blocked at system/channel level, runtime request won't help → go settings
    // - If notif permission isn't granted yet (and notificationsEnabled is true), runtime request may help → grant
    val shouldOpenSettings = (needNotifPermission && !notificationsEnabled) // notif blocked by system/channel
    val primaryText = if (shouldOpenSettings) "Open settings" else "Grant permissions"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission required") },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = {
                    if (shouldOpenSettings) onOpenSettings() else onGrant()
                }
            ) { Text(primaryText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}