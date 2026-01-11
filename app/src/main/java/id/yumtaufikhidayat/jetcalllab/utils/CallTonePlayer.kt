package id.yumtaufikhidayat.jetcalllab.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import id.yumtaufikhidayat.jetcalllab.R
import id.yumtaufikhidayat.jetcalllab.enum.ToneType
import id.yumtaufikhidayat.jetcalllab.state.CallState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallTonePlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var soundPool: SoundPool? = null
    private var connectingSoundId = 0
    private var reconnectingSoundId = 0

    private val loaded = CompletableDeferred<Unit>()
    private var loadedCount = 0
    private val totalToLoad = 2

    private var currentStreamId = 0
    private var currentTone: ToneType? = null

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.setOnLoadCompleteListener { _, _, status ->
            // success
            if (status == 0) {
                loadedCount++
                if (loadedCount >= totalToLoad && !loaded.isCompleted) loaded.complete(Unit)
            }
        }

        connectingSoundId = soundPool?.load(appContext, R.raw.ring_connecting, 1) ?: 0
        reconnectingSoundId = soundPool?.load(appContext, R.raw.ring_reconnecting, 1) ?: 0
    }

    fun onCallState(state: CallState) {
        when (state) {
            // Connecting tone start condition
            is CallState.WaitingAnswer, is CallState.ExchangingIce -> start(ToneType.CONNECTING)

            // Reconnecting tone start condition
            is CallState.Reconnecting -> start(ToneType.RECONNECTING)

            // Stop condition
            is CallState.Connected, is CallState.ConnectedFinal,
            is CallState.Failed, is CallState.FailedFinal,
            is CallState.Ending, is CallState.Idle -> stop()

            else -> Unit
        }
    }

    fun start(tone: ToneType) {
        // don't restart if same tone is running
        if (currentTone == tone && currentStreamId != 0) return

        scope.launch {
            runCatching {
                loaded.await()
                stopInternal()

                val soundId = when (tone) {
                    ToneType.CONNECTING -> connectingSoundId
                    ToneType.RECONNECTING -> reconnectingSoundId
                }

                // loop = -1 -> infinite
                currentStreamId = soundPool?.play(
                    soundId,
                    1f, 1f, // left/right volume
                    1,
                    -1,
                    1f // rate
                ) ?: 0
                currentTone = tone
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (currentStreamId != 0) {
            runCatching { soundPool?.stop(currentStreamId) }
        }
        currentStreamId = 0
        currentTone = null
    }

    fun release() {
        stopInternal()
        runCatching { soundPool?.release() }
        scope.cancel()
    }
}