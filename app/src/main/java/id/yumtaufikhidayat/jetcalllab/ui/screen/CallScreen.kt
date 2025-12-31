package id.yumtaufikhidayat.jetcalllab.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import id.yumtaufikhidayat.jetcalllab.formatHms
import id.yumtaufikhidayat.jetcalllab.state.CallState
import id.yumtaufikhidayat.jetcalllab.ui.viewmodel.CallViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CallScreen(
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
    var roomId by rememberSaveable { mutableStateOf("room_test_") }
    val canStartSession = remember(state, roomId) {
        roomId.isNotBlank() && (
                state is CallState.Idle
                        // Remove both condition below if want to end call instead of retry call
//                        || state is CallState.Failed
//                        || state is CallState.FailedFinal
                )
    }
    val elapsed by viewModel.elapsedSeconds.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()

    val inCall = state !is CallState.Idle

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "State: ${state::class.simpleName}")

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = roomId,
            onValueChange = { roomId = it },
            label = { Text("Room ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (micPermission.status.isGranted) {
                        viewModel.startCaller(roomId.trim())
                    } else {
                        micPermission.launchPermissionRequest()
                    }
                },
                enabled = canStartSession
            ) {
                Text(text = "Call")
            }

            Button(
                onClick = {
                    if (micPermission.status.isGranted) {
                        viewModel.joinCallee( roomId.trim())
                    } else {
                        micPermission.launchPermissionRequest()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = canStartSession
            ) {
                Text("Answer")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                modifier = modifier.weight(1f),
                onClick = viewModel::toggleMute,
                enabled = inCall
            ) { Text(if (isMuted) "Muted" else "Mute") }

            Button(
                modifier = modifier.weight(1f),
                onClick = viewModel::endCall,
                enabled = state !is CallState.Idle,
            ) { Text("End") }

            Button(
                modifier = modifier.weight(1f),
                onClick = viewModel::toggleSpeaker,
                enabled = inCall
            ) { Text(if (isSpeakerOn) "Speaker On" else "Speaker Off") }
        }

        Text("Mic permission status: ${if (micPermission.status.isGranted) "granted" else "not granted"}")

        Text(text = "Call time: ${elapsed.formatHms()}")

        Text(text = "Mute: ${if (isMuted) "on" else "off"}")

        Text(text = "Speaker: ${if (isSpeakerOn) "on" else "off"}")
    }
}