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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import id.yumtaufikhidayat.jetcalllab.enum.CallRole
import id.yumtaufikhidayat.jetcalllab.enum.TempoPhase
import id.yumtaufikhidayat.jetcalllab.ext.formatHms
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
    val elapsed by viewModel.elapsedSeconds.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val isBluetoothActive by viewModel.isBluetoothActive.collectAsState()
    val isBluetoothAvailable by viewModel.isBluetoothAvailable.collectAsState()
    val isWiredActive by viewModel.isWiredActive.collectAsState()
    val tempo by viewModel.tempo.collectAsState()
    val role by viewModel.role.collectAsState()

    val isInSession = when (state) {
        is CallState.Preparing,
        is CallState.CreatingOffer,
        is CallState.WaitingAnswer,
        is CallState.WaitingOffer,
        is CallState.CreatingAnswer,
        is CallState.ExchangingIce,
        is CallState.ConnectedFinal,
        is CallState.Connected,
        is CallState.Reconnecting -> true
        else -> false // Failed/FailedFinal/Ending/Idle
    }
    val isRoomInputEnabled = !isInSession
    val callAnswerEnabled = !isInSession && roomId.isNotBlank()

    val showConnectingTempo = (state is CallState.WaitingAnswer || state is CallState.ExchangingIce) && tempo?.phase == TempoPhase.CONNECTING
    val showReconnectTempo = (state is CallState.Reconnecting) && tempo?.phase == TempoPhase.RECONNECTING

    val roleText = when (role) {
        CallRole.CALLER -> "Caller"
        CallRole.CALLEE -> "Callee"
        null -> "—"
    }

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
            enabled = isRoomInputEnabled,
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
                enabled = callAnswerEnabled
            ) {
                Text(text = "Call")
            }

            Button(
                onClick = {
                    if (micPermission.status.isGranted) {
                        viewModel.joinCallee(roomId.trim())
                    } else {
                        micPermission.launchPermissionRequest()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = callAnswerEnabled
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
                enabled = isInSession
            ) { Text(if (isMuted) "Muted" else "Mute") }

            Button(
                modifier = modifier.weight(1f),
                onClick = viewModel::endCall,
                enabled = isInSession,
            ) { Text("End") }

            Button(
                modifier = modifier.weight(1f),
                onClick = viewModel::toggleSpeaker,
                enabled = isInSession
            ) { Text(if (isSpeakerOn) "Speaker On" else "Speaker Off") }
        }

        Text("Mic permission status: ${if (micPermission.status.isGranted) "granted" else "not granted"}")

        Text(text = "Role: $roleText",)

        Text(text = "Call time: ${elapsed.formatHms()}")

        Text(text = "Mute: ${if (isMuted) "on" else "off"}")

        Text(text = "Speaker: ${if (isSpeakerOn) "on" else "off"}")

        Text(text = "Bluetooth: ${if (isBluetoothActive) "on" else "off"}")

        if (showConnectingTempo) {
            Text("Waiting answer… ${tempo?.remainingSeconds}s left")
        }

        if (showReconnectTempo) {
            Text("Reconnecting… ${tempo?.remainingSeconds}s left")
        }

        when (val s = state) {
            is CallState.Connected -> {
                Text("Connected")
                Text("Via: ${s.via}")
            }

            is CallState.ConnectedFinal -> {
                Text("Connected")
                Text("Via: ${s.via}")
            }

            is CallState.FailedFinal -> {
                Text("Failed: ${s.reason}")
            }

            else -> Unit
        }

        Text(text = "Bluetooth: ${if (isBluetoothAvailable) "available" else "unavailable" }")

        Text(text = "Wired headset: ${if (isWiredActive) "on" else "off"}")
    }
}