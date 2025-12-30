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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var roomId by rememberSaveable { mutableStateOf("room_test_") }
    val canStartSession = remember(state, roomId) {
        roomId.isNotBlank() && (
                state is CallState.Idle ||
                        // hapus dua kondisi di bawah ini jika ingin end call alih-alih retry call
                        state is CallState.Failed ||
                        state is CallState.FailedFinal
                )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onUiVisible()
                Lifecycle.Event.ON_STOP -> viewModel.onUiHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                        viewModel.startCaller(context.applicationContext, roomId.trim())
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
                        viewModel.joinCallee(context, roomId.trim())
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

        Button(
            modifier = modifier.fillMaxWidth(),
            onClick = viewModel::endCall,
            enabled = state !is CallState.Idle,
        ) { Text("End") }

        Text("Mic permission status: ${if (micPermission.status.isGranted) "granted" else "not granted"}")

        Spacer(Modifier.height(16.dp))

        Text(text = "State: ${state::class.simpleName}")
    }
}