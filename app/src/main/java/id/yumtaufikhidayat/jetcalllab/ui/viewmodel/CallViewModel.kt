package id.yumtaufikhidayat.jetcalllab.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.yumtaufikhidayat.jetcalllab.model.CallTempo
import id.yumtaufikhidayat.jetcalllab.service.CallService
import id.yumtaufikhidayat.jetcalllab.state.CallState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallViewModel(application: Application): AndroidViewModel(application) {

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state = _state.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn = _isSpeakerOn.asStateFlow()

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive = _isBluetoothActive.asStateFlow()

    private val _isBluetoothAvailable = MutableStateFlow(false)
    val isBluetoothAvailable = _isBluetoothAvailable.asStateFlow()

    private val _isWiredActive = MutableStateFlow(false)
    val isWiredActive = _isWiredActive.asStateFlow()

    private val _tempo = MutableStateFlow<CallTempo?>(null)
    val tempo = _tempo.asStateFlow()

    private var callService: CallService? = null
    private var serviceBound = false

    private var collectJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val binder = service as CallService.LocalBinder
            callService = binder.service()
            serviceBound = true

            // bridge flows from service → viewModel
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            collectJob?.cancel()
            collectJob = null

            serviceBound = false
            callService = null

            _state.value = CallState.Idle
            _elapsedSeconds.value = 0L
            _tempo.value = null
            resetUiFlagsToIdle()
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, CallService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = callService ?: return

        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            launch { service.state.collect { state ->
                _state.value = state

                when (state) {
                    is CallState.FailedFinal,
                    is CallState.Failed,
                    is CallState.Ending,
                    is CallState.Idle -> {
                        // back to idle state
                        _elapsedSeconds.value = 0L
                        _tempo.value = null
                        resetUiFlagsToIdle()
                    }
                    else -> Unit
                }
            } }
            launch { service.elapsedSeconds.collect { time -> _elapsedSeconds.value = time } }
            launch { service.isMuted.collect { mute -> _isMuted.value = mute } }
            launch { service.isSpeakerOn.collect { on -> _isSpeakerOn.value = on } }
            launch { service.isBluetoothActive.collect { active -> _isBluetoothActive.value = active } }
            launch { service.isBluetoothAvailable.collect { available -> _isBluetoothAvailable.value = available } }
            launch { service.isWiredActive.collect { wired -> _isWiredActive.value = wired } }
            launch { service.tempo.collect { tempo -> _tempo.value = tempo } }
        }
    }

    fun toggleMute() {
        callService?.toggleMute()
    }

    fun toggleSpeaker() {
        callService?.toggleSpeaker()
    }

    fun startCaller(roomId: String) {
        callService?.startCaller(roomId)
    }

    fun joinCallee(roomId: String) {
        callService?.joinCallee(roomId)
    }

    fun endCall() {
        callService?.endCall()
    }

    private fun resetUiFlagsToIdle() {
        _isMuted.value = false
        _isSpeakerOn.value = false
        _isBluetoothActive.value = false
    }

    override fun onCleared() {
        val context = getApplication<Application>()
        if (serviceBound) {
            context.unbindService(connection)
            serviceBound = false
        }
        callService = null
        super.onCleared()
    }
}