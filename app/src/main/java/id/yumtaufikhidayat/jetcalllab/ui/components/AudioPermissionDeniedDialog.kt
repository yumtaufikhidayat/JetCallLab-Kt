package id.yumtaufikhidayat.jetcalllab.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun AudioPermissionDeniedDialog(
    isCall: Boolean,
    onDismiss: () -> Unit,
    onOpenMicSettings: () -> Unit,
) {
    val title = "Microphone blocked"
    val message = buildString {
        append("You denied microphone permission.\n\n")
        append("To ${if (isCall) "start" else "answer"} a call, enable Microphone in App Settings.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onOpenMicSettings) { Text("Open mic settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
