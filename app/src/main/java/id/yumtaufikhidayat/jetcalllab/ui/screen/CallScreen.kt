package id.yumtaufikhidayat.jetcalllab.ui.screen

import android.Manifest
import android.os.Build
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import id.yumtaufikhidayat.jetcalllab.enum.CallRole
import id.yumtaufikhidayat.jetcalllab.enum.GateStage
import id.yumtaufikhidayat.jetcalllab.enum.PendingAction
import id.yumtaufikhidayat.jetcalllab.enum.TempoPhase
import id.yumtaufikhidayat.jetcalllab.ext.formatHms
import id.yumtaufikhidayat.jetcalllab.ext.openAppNotificationSettings
import id.yumtaufikhidayat.jetcalllab.ext.openAppSettings
import id.yumtaufikhidayat.jetcalllab.state.CallState
import id.yumtaufikhidayat.jetcalllab.ui.components.AudioPermissionDeniedDialog
import id.yumtaufikhidayat.jetcalllab.ui.components.PermissionGateDialog
import id.yumtaufikhidayat.jetcalllab.ui.viewmodel.CallViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CallScreen(
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val elapsed by viewModel.elapsedSeconds.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val isBluetoothActive by viewModel.isBluetoothActive.collectAsState()
    val isBluetoothAvailable by viewModel.isBluetoothAvailable.collectAsState()
    val isWiredActive by viewModel.isWiredActive.collectAsState()
    val tempo by viewModel.tempo.collectAsState()
    val role by viewModel.role.collectAsState()
    var roomId by rememberSaveable { mutableStateOf("room_test_") }

    // gate / pending action
    var gateStage by rememberSaveable { mutableStateOf<GateStage?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<PendingAction?>(null) }

    // Flag to track if user deny runtime permission in just
    var runtimeNotifDenied by rememberSaveable { mutableStateOf(false) }
    var isRequestingPermission by rememberSaveable { mutableStateOf(false) }

    fun startPendingAction() {
        when (pendingAction) {
            PendingAction.CALL -> viewModel.startCaller(roomId.trim())
            PendingAction.ANSWER -> viewModel.joinCallee(roomId.trim())
            else -> Unit
        }
        pendingAction = null
    }

    // mic permissions
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { isGranted ->
        // Callback triggered if user choose Allow or Deny
        gateStage = if (!isGranted) GateStage.AUDIO_SETTINGS else GateStage.GATE
    }

    val needNotifPermission = Build.VERSION.SDK_INT >= 33
    val notifPermission = if (needNotifPermission) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) { isGranted ->
            isRequestingPermission = false

            if (isGranted) {
                val isSystemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (isSystemEnabled) {
                    startPendingAction()
                    gateStage = null
                } else {
                    gateStage = GateStage.GATE
                }
            } else {
                runtimeNotifDenied = true
                gateStage = GateStage.GATE
            }
        }
    } else null

    val micGranted = micPermission.status.isGranted
    val notifGranted = !needNotifPermission || (notifPermission?.status?.isGranted == true)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

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
        else -> false
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

    fun onStartAction(action: PendingAction) {
        pendingAction = action
        val isNotifOk = if (needNotifPermission) notifGranted && notificationsEnabled else notificationsEnabled

        if (micGranted && isNotifOk) {
            startPendingAction()
        } else {
            gateStage = GateStage.GATE
        }
    }

    fun proceedFromGate() {
        // MIC first
        if (!micGranted) {
            gateStage = null
            isRequestingPermission = true
            micPermission.launchPermissionRequest()
            return
        }

        // notif runtime (Android 13+)
        if (needNotifPermission && !notifGranted) {
            gateStage = null
            if (runtimeNotifDenied) {
                context.openAppNotificationSettings()
            } else {
                isRequestingPermission = true
                notifPermission?.launchPermissionRequest()
            }
            return
        }

        // notif blocked by system/channel
        if (!notificationsEnabled) {
            gateStage = null
            context.openAppNotificationSettings()
            return
        }

        // all ok -> auto start call/answer directly
        startPendingAction()
        gateStage = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isRequestingPermission) return@LifecycleEventObserver

                if (pendingAction != null) {
                    val isNotifOk = if (needNotifPermission) notifGranted && notificationsEnabled else notificationsEnabled

                    if (micGranted && isNotifOk) {
                        startPendingAction()
                        gateStage = null
                    } else {
                        if (gateStage == null) {
                            gateStage = GateStage.GATE
                        }
                    }
                }
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
                onClick = { onStartAction(PendingAction.CALL) },
                enabled = callAnswerEnabled
            ) { Text("Call") }

            Button(
                modifier = Modifier.weight(1f),
                onClick = { onStartAction(PendingAction.ANSWER) },
                enabled = callAnswerEnabled
            ) { Text("Answer") }
        }

        if (gateStage == GateStage.GATE) {
            PermissionGateDialog(
                micGranted = micGranted,
                notifGranted = notifGranted,
                needNotifPermission = needNotifPermission,
                notificationsEnabled = notificationsEnabled,
                runtimeNotifDenied = runtimeNotifDenied,
                isCall = (pendingAction == PendingAction.CALL),
                onDismiss = {
                    gateStage = null
                    pendingAction = null
                    runtimeNotifDenied = false
                },
                onPrimary = { proceedFromGate() },
                onOpenNotifSettings = { context.openAppNotificationSettings() }
            )
        }

        if (gateStage == GateStage.AUDIO_SETTINGS) {
            AudioPermissionDeniedDialog(
                isCall = (pendingAction == PendingAction.CALL),
                onDismiss = {
                    gateStage = null
                    pendingAction = null
                },
                onOpenMicSettings = { context.openAppSettings() }
            )
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

        Text("Mic permission: ${if (micGranted) "granted" else "not granted"}")
        if (needNotifPermission) {
            val status = notifPermission?.status
            Text("Notification permission: ${if (status?.isGranted == true) "granted" else "not granted"}")
            Text("Should show rationale: ${status?.shouldShowRationale}")
        }

        Text("Role: $roleText")
        Text("Call time: ${elapsed.formatHms()}")
        Text("Mute: ${if (isMuted) "on" else "off"}")
        Text("Speaker: ${if (isSpeakerOn) "on" else "off"}")
        Text("Bluetooth: ${if (isBluetoothActive) "on" else "off"}")

        if (showConnectingTempo) Text("Waiting answer… ${tempo?.remainingSeconds}s left")
        if (showReconnectTempo) Text("Reconnecting… ${tempo?.remainingSeconds}s left")

        when (val callState = state) {
            is CallState.Connected, is CallState.ConnectedFinal -> {
                val via = when (callState) {
                    is CallState.Connected -> callState.via
                    is CallState.ConnectedFinal -> callState.via
                    else -> ""
                }
                Text("${state::class.simpleName} via $via")
            }
            is CallState.FailedFinal -> Text("Failed: ${callState.reason}")
            else -> Unit
        }

        Text("Bluetooth: ${if (isBluetoothAvailable) "available" else "unavailable"}")
        Text("Wired headset: ${if (isWiredActive) "available" else "unavailable"}")
    }
}