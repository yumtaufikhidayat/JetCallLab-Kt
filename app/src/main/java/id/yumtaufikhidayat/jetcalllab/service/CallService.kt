package id.yumtaufikhidayat.jetcalllab.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import id.yumtaufikhidayat.jetcalllab.R
import id.yumtaufikhidayat.jetcalllab.state.CallState
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

    private var callStartElapsedMs: Long? = null
    private var timerJob: Job? = null

    private var btJob: Job? = null

    inner class LocalBinder : Binder() {
        fun service(): CallService = this@CallService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder  = binder

    override fun onCreate() {
        super.onCreate()
        webRtc.setListener(this)

        btJob?.cancel()
        btJob = serviceScope.launch {
            webRtc.isBluetoothActive.collect { active ->
                _isBluetoothActive.value = active
            }
        }
    }

    override fun onDestroy() {
        btJob?.cancel()
        btJob = null

        webRtc.setListener(null)
        webRtc.endCall()
        stopTimer(reset = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    fun startCaller(roomId: String) {
        startAsForegroundIfNeeded()


        webRtc.setSpeakerOn(_isSpeakerOn.value)
        webRtc.setMuted(_isMuted.value)
        webRtc.startCallAsCaller(applicationContext, roomId)
    }

    fun joinCallee(roomId: String) {
        startAsForegroundIfNeeded()

        webRtc.setSpeakerOn(_isSpeakerOn.value)
        webRtc.setMuted(_isMuted.value)
        webRtc.joinCallAsCallee(applicationContext, roomId)
    }

    fun endCall() {
        webRtc.setMuted(false)
        webRtc.setSpeakerOn(false)

        _isMuted.value = false
        _isSpeakerOn.value = false

        webRtc.endCall()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetAudioUiState() {
        _isMuted.value = false
        _isSpeakerOn.value = false
        webRtc.setMuted(false)
        webRtc.setSpeakerOn(false)
    }

    override fun onState(state: CallState) {
        _state.value = state

        when (state) {
            is CallState.ConnectedFinal -> startTimerIfNeeded()
            is CallState.Idle,
            is CallState.Ending,
            is CallState.FailedFinal -> {
                resetAudioUiState()
                stopTimer(reset = true)
                // optional: stop service otomatis saat gagal
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> Unit
        }
    }

    override fun onError(message: String) {
        _state.value = CallState.Failed(message)
    }

    private fun startTimerIfNeeded() {
        if (timerJob != null) return
        if (callStartElapsedMs == null) callStartElapsedMs = SystemClock.elapsedRealtime()

        timerJob = serviceScope.launch {
            while (true) {
                val start = callStartElapsedMs ?: break
                _elapsedSeconds.value = (SystemClock.elapsedRealtime() - start) / 1000L
                delay(1000)
            }
        }
    }

    private fun stopTimer(reset: Boolean) {
        timerJob?.cancel()
        timerJob = null
        if (reset) {
            callStartElapsedMs = null
            _elapsedSeconds.value = 0L
        }
    }

    private fun startAsForegroundIfNeeded() {
        createNotificationChannelIfNeeded()

        val notification = NotificationCompat.Builder(this, "call_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Call in progress")
            .setContentText("Room: ...")
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
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

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        webRtc.setMuted(next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        _isSpeakerOn.value = next
        webRtc.setSpeakerOn(next)
    }
}