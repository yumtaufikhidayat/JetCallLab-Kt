package id.yumtaufikhidayat.jetcalllab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import id.yumtaufikhidayat.jetcalllab.MainActivity
import id.yumtaufikhidayat.jetcalllab.R
import id.yumtaufikhidayat.jetcalllab.enum.CallRole
import id.yumtaufikhidayat.jetcalllab.enum.TempoPhase
import id.yumtaufikhidayat.jetcalllab.model.CallTempo
import id.yumtaufikhidayat.jetcalllab.state.CallState
import id.yumtaufikhidayat.jetcalllab.utils.CallTonePlayer
import id.yumtaufikhidayat.jetcalllab.utils.ProximityController
import id.yumtaufikhidayat.jetcalllab.utils.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class CallService : Service(), WebRtcManager.Listener {

    private val webRtc = WebRtcManager()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive: StateFlow<Boolean> = _isBluetoothActive.asStateFlow()

    private val _isBluetoothAvailable = MutableStateFlow(false)
    val isBluetoothAvailable: StateFlow<Boolean> = _isBluetoothAvailable.asStateFlow()

    private val _isWiredActive = MutableStateFlow(false)
    val isWiredActive: StateFlow<Boolean> = _isWiredActive.asStateFlow()

    private val _tempo = MutableStateFlow<CallTempo?>(null)
    val tempo: StateFlow<CallTempo?> = _tempo.asStateFlow()

    private val _role = MutableStateFlow<CallRole?>(null)
    val role: StateFlow<CallRole?> = _role.asStateFlow()

    private var autoHangupJob: Job? = null
    private var timerJob: Job? = null
    private var deviceJob: Job? = null
    private var notifJob: Job? = null

    @Volatile
    private var isAutoTerminating = false

    private var callStartElapsedMs: Long? = null
    private var callStartWallTimeMs: Long? = null

    private var proximity: ProximityController? = null
    private var tonePlayer: CallTonePlayer? = null

    inner class LocalBinder : Binder() {
        fun service(): CallService = this@CallService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        webRtc.setListener(this)
        webRtc.initAudioMonitoring(applicationContext)

        tonePlayer = CallTonePlayer(this)

        proximity = ProximityController(this)
        proximity?.start()

        // Observe device state from WebRtcManager
        deviceJob = serviceScope.launch {
            launch {
                webRtc.isBluetoothActive.collect { active ->
                    _isBluetoothActive.value = active
                    updateProximityState()
                }
            }

            launch {
                webRtc.isBluetoothAvailable.collect { available ->
                    _isBluetoothAvailable.value = available
                    updateProximityState()
                }
            }

            launch {
                webRtc.isWiredActive.collect { wired ->
                    _isWiredActive.value = wired
                    updateProximityState()
                }
            }
        }
    }

    override fun onDestroy() {
        stopNotificationUpdates()
        autoHangupJob?.cancel()
        autoHangupJob = null

        deviceJob?.cancel()
        deviceJob = null

        runCatching { tonePlayer?.release() }

        proximity?.stop()

        webRtc.setListener(null)
        webRtc.endCall()

        stopTimer(reset = true)
        serviceScope.cancel()

        super.onDestroy()
    }

    // Call entry points
    fun startCaller(roomId: String) {
        _role.value = CallRole.CALLER
        startAsForegroundIfNeeded()
        webRtc.setSpeakerOn(_isSpeakerOn.value)
        webRtc.setMuted(_isMuted.value)
        webRtc.startCallAsCaller(applicationContext, roomId)
    }

    fun joinCallee(roomId: String) {
        _role.value = CallRole.CALLEE
        startAsForegroundIfNeeded()
        webRtc.setSpeakerOn(_isSpeakerOn.value)
        webRtc.setMuted(_isMuted.value)
        webRtc.joinCallAsCallee(applicationContext, roomId)
    }

    fun endCall() {
        if (isAutoTerminating) return
        isAutoTerminating = true

        // Stop tone ASAP
        runCatching { tonePlayer?.stop() }
        _tempo.value = null

        resetAudioUiState()
        webRtc.endCall()
    }

    override fun onTempo(
        phase: TempoPhase,
        elapsedSeconds: Long,
        remainingSeconds: Long,
        timeoutSeconds: Long
    ) {
        _tempo.value = CallTempo(
            phase = phase,
            elapsedSeconds = elapsedSeconds,
            remainingSeconds = remainingSeconds,
            timeoutSeconds = timeoutSeconds
        )
    }
    
    // WebRtcManager.Listener
    override fun onState(state: CallState) {
        serviceScope.launch {
            // If it is auto terminating, ignore the additional states from the engine.
            if (isAutoTerminating && state !is CallState.Ending && state !is CallState.Idle) return@launch

            _state.value = state
            updateProximityState()

            // tone follows state
            tonePlayer?.onCallState(state)

            when (state) {
                is CallState.ConnectedFinal -> {
                    startTimerIfNeeded()
                }

                is CallState.Connected -> updateForegroundNotificationForCurrentState()
                is CallState.Reconnecting -> updateForegroundNotificationForCurrentState()

                is CallState.FailedFinal -> {
                    resetAudioUiState()
                    // automatically end call + stop service
                    scheduleAutoHangup(reasonForUser = state.reason)
                }

                is CallState.Failed -> {
                    resetAudioUiState()
                    scheduleAutoHangup(reasonForUser = state.reason)
                }

                is CallState.Ending -> {
                    // Transitional state. Do NOT stop service here.
                    resetAudioUiState()
                    stopTimer(reset = true)
                    // optional: keep foreground until Idle for smoother UX
                }

                is CallState.Idle -> {
                    stopNotificationUpdates()
                    resetAudioUiState()
                    stopTimer(reset = true)

                    _tempo.value = null
                    isAutoTerminating = false
                    autoHangupJob?.cancel()
                    autoHangupJob = null
                    _role.value = null

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                else -> Unit
            }
        }
    }

    override fun onError(message: String) {
        serviceScope.launch {
            _state.value = CallState.Failed(message)
            updateProximityState()
            tonePlayer?.onCallState(CallState.Failed(message))
            scheduleAutoHangup(reasonForUser = message)
        }
    }
    
    // UI actions
    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        webRtc.setMuted(next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        webRtc.setSpeakerOn(next)
        updateProximityState()
    }

    
    // Proximity logic (SINGLE SOURCE OF TRUTH)
    private fun updateProximityState() {
        val inCall = when (_state.value) {
            is CallState.ConnectedFinal,
            is CallState.Connected,
            is CallState.Reconnecting -> true
            else -> false
        }

        val bluetoothEffective = _isBluetoothAvailable.value || _isBluetoothActive.value

        proximity?.updateCallConditions(
            inCall = inCall,
            speakerOn = _isSpeakerOn.value,
            bluetoothActive = bluetoothEffective,
            wiredHeadset = _isWiredActive.value
        )
    }
    
    // Timer
    private fun startTimerIfNeeded() {
        if (timerJob != null) return
        if (callStartElapsedMs == null) callStartElapsedMs = SystemClock.elapsedRealtime()
        if (callStartWallTimeMs == null) callStartWallTimeMs = System.currentTimeMillis()

        timerJob = serviceScope.launch {
            while (true) {
                val start = callStartElapsedMs ?: break
                _elapsedSeconds.value =
                    (SystemClock.elapsedRealtime() - start) / 1000L
                delay(1000)
            }
        }
    }

    private fun stopTimer(reset: Boolean) {
        timerJob?.cancel()
        timerJob = null
        if (reset) {
            callStartWallTimeMs = null
            callStartElapsedMs = null
            _elapsedSeconds.value = 0L
        }
    }

    private fun resetAudioUiState() {
        _isMuted.value = false
        _isSpeakerOn.value = false
        webRtc.setMuted(false)
        webRtc.setSpeakerOn(false)
    }

    // Foreground
    private fun startAsForegroundIfNeeded() {
        createNotificationChannelIfNeeded()

        val notification = buildCallNotification(
            title = "JetCallLab",
            text = "Connecting…",
            useChronometer = false,
            whenMs = null
        )

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        startNotificationUpdatesIfNeeded()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "call_channel",
                "Call",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotificationForCurrentState() {
        val callState = _state.value
        val chrono = shouldShowChronometer(callState)
        val whenMs = if (chrono) callStartWallTimeMs else null

        val title = "JetCallLab"
        val text = when (callState) {
            is CallState.ConnectedFinal, is CallState.Connected -> "In call"
            is CallState.Reconnecting -> "Reconnecting… attempt ${callState.attempt}"
            is CallState.WaitingAnswer -> "Waiting answer…"
            is CallState.ExchangingIce -> "Exchanging ICE…"
            is CallState.Failed, is CallState.FailedFinal -> "Call failed"
            else -> "Connecting…"
        }

        val notif = buildCallNotification(
            title = title,
            text = text,
            useChronometer = chrono,
            whenMs = whenMs
        )

        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }


    private fun scheduleAutoHangup(
        reasonForUser: String?,
        delayMs: Long = 1200L // delay for user to read a reason
    ) {
        if (autoHangupJob != null) return
        if (isAutoTerminating) return

        autoHangupJob = serviceScope.launch {
            // Stop tone ASAP (to handle "hangup")
            runCatching { tonePlayer?.stop() }

            _tempo.value = null

            // Update notif for consistency
            reasonForUser?.let { updateForegroundNotificationForCurrentState() }
            delay(delayMs)

            // Prevent re-entrancy
            isAutoTerminating = true
            webRtc.endCall()
        }
    }

    private fun startNotificationUpdatesIfNeeded() {
        if (notifJob != null) return

        notifJob = serviceScope.launch {
            combine(_state, _elapsedSeconds, _tempo) { state, elapsed, tempo -> Triple(state, elapsed, tempo) }
                .distinctUntilChanged()
                .collect {
                    updateForegroundNotificationForCurrentState()
                }
        }
    }

    private fun stopNotificationUpdates() {
        notifJob?.cancel()
        notifJob = null
    }

    private fun buildCallNotification(
        title: String,
        text: String,
        useChronometer: Boolean,
        whenMs: Long?
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply { // Change MainActivity::class.java with actual UI
            action = ACTION_SHOW_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // let update notification so it never always triggered
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setShowWhen(useChronometer)
            .setWhen(whenMs ?: System.currentTimeMillis())
            .setUsesChronometer(useChronometer)
            .build()
    }

    private fun shouldShowChronometer(state: CallState): Boolean =
        state is CallState.ConnectedFinal || state is CallState.Connected || state is CallState.Reconnecting

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundIfNeeded() // startForeground directly, do not wait the call
        return START_STICKY
    }

    companion object {
        const val CHANNEL_ID = "call_channel"
        const val NOTIF_ID = 1001

        const val ACTION_SHOW_CALL = "jetcalllab.action.SHOW_CALL"
        const val ACTION_END_CALL = "jetcalllab.action.END_CALL"
    }
}