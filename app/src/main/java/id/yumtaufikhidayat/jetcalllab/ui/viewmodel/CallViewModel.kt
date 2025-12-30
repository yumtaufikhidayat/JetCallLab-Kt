package id.yumtaufikhidayat.jetcalllab.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import id.yumtaufikhidayat.jetcalllab.state.CallState
import id.yumtaufikhidayat.jetcalllab.utils.WebRtcManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CallViewModel: ViewModel(), WebRtcManager.Listener {

    private val webRtc = WebRtcManager()

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state = _state.asStateFlow()

    @Volatile private var uiVisible = true

    init {
        webRtc.setListener(this)
    }

    fun onUiVisible() { uiVisible = true }
    fun onUiHidden() { uiVisible = false }

    fun startCaller(context: Context, roomId: String) {
        webRtc.startCallAsCaller(context, roomId)
    }

    fun joinCallee(context: Context, roomId: String) {
        webRtc.joinCallAsCallee(context, roomId)
    }

    fun endCall() {
        webRtc.endCall()
    }

    override fun onState(state: CallState) {
        _state.value = state
    }

    override fun onError(message: String) {
        _state.value = CallState.Failed(message)
    }

    override fun onCleared() {
        webRtc.setListener(null)
        webRtc.endCall()
        super.onCleared()
    }
}