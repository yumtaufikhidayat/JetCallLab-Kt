package id.yumtaufikhidayat.jetcalllab.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import id.yumtaufikhidayat.jetcalllab.enum.AudioRoute
import id.yumtaufikhidayat.jetcalllab.state.CallState
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
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcManager {

    private var listener: Listener? = null
    private var callScope: CoroutineScope? = null

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var audioDeviceModule: AudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var eglBase: EglBase? = null

    // Signaling
    private val signaling = FirestoreSignaling()
    private var offerListener: ListenerRegistration? = null
    private var answerListener: ListenerRegistration? = null
    private var candListener: ListenerRegistration? = null

    private var onLocalIceCandidate: ((IceCandidate) -> Unit)? = null
    private var isRemoteSdpSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    @Volatile
    private var sessionActive = false

    @Volatile
    private var finalEmitted = false
    private var connectTimeoutJob: Job? = null

    @Volatile
    private var desiredRoute: AudioRoute = AudioRoute.EARPIECE

    @Volatile
    private var lastScoState: Int = AudioManager.SCO_AUDIO_STATE_DISCONNECTED

    private var audioManager: AudioManager? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var prevAudioMode: Int? = null
    private var prevSpeakerOn: Boolean? = null
    private var prevBluetoothScoOn: Boolean? = null

    private var appContext: Context? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive: StateFlow<Boolean> = _isBluetoothActive.asStateFlow()

    private val iceCount = mutableMapOf(
        "host" to 0, "srflx" to 0, "prflx" to 0, "relay" to 0, "unknown" to 0
    )

    private fun guardActive(): Boolean {
        if (!sessionActive) {
            // optional: log "ignored because session ended"
            return false
        }
        return true
    }

    private fun ensureCallScope() {
        callScope?.cancel()
        callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun cancelCallScope() {
        callScope?.cancel()
        callScope = null
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    // This method used for:
    // close peer connection, dispose audio track/source/factory
    fun endCall() {
        sessionActive = false

        connectTimeoutJob?.cancel()
        connectTimeoutJob = null

        cancelCallScope()
        listener?.onState(CallState.Ending)

        // stop callbacks ASAP
        onLocalIceCandidate = null

        // remove firestore listeners ASAP
        offerListener?.remove(); offerListener = null
        answerListener?.remove(); answerListener = null
        candListener?.remove(); candListener = null

        resetSessionState()

        val pc = peerConnection.also { peerConnection = null }
        val track = audioTrack.also { audioTrack = null }
        val source = audioSource.also { audioSource = null }
        val adm = audioDeviceModule.also { audioDeviceModule = null }
        val factory = peerConnectionFactory.also { peerConnectionFactory = null }
        val egl = eglBase.also { eglBase = null }

        runCatching { pc?.close() }
        runCatching { pc?.dispose() }

        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { adm?.release() }
        runCatching { factory?.dispose() }
        runCatching { egl?.release() }

        releaseAudioResources()
        clearAudioState()
        _isBluetoothActive.value = false
        lastScoState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED

        listener?.onState(CallState.Idle)
    }

    private fun handleRemoteIce(candidate: IceCandidate) {
        if (!guardActive()) return
        val pc = peerConnection ?: return

        if (!isRemoteSdpSet) {
            pendingIce.add(candidate)
            Log.d("RTC", "REMOTE ICE queued pending=${pendingIce.size}")
            return
        }

        val ok = pc.addIceCandidate(candidate)
        Log.d(
            "RTC",
            "REMOTE ICE applied ok=$ok mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex}"
        )

        if (!ok) {
            Log.w("RTC", "addIceCandidate=false, queue again")
            pendingIce.add(candidate)
        }
    }


    private fun flushPendingIce() {
        val peerConnection = peerConnection ?: return
        pendingIce.forEach { peerConnection.addIceCandidate(it) }
        pendingIce.clear()
    }


    interface Listener {
        fun onState(state: CallState)
        fun onError(message: String)
    }

    fun init(context: Context) {
        if (peerConnectionFactory != null) return

        appContext = context.applicationContext
        val mContext = appContext ?: return

        setupAudioManager(mContext)
        registerAudioDeviceMonitoring(mContext)
        registerScoReceiver(mContext)

        val initializationOptions = PeerConnectionFactory
            .InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(initializationOptions)

        eglBase = EglBase.create()

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun setupAudioManager(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am

        prevAudioMode = am.mode
        prevSpeakerOn = am.isSpeakerphoneOn
        prevBluetoothScoOn = am.isBluetoothScoOn

        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { /* no-op */ }

        am.apply {
            stopBluetoothSco()
            isBluetoothScoOn = false
            isBluetoothA2dpOn = false

            mode = AudioManager.MODE_IN_COMMUNICATION
            isSpeakerphoneOn = false

            @Suppress("DEPRECATION")
            requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun createAudioTrack() {
        if (audioTrack != null && audioSource != null) return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }

        val factory = peerConnectionFactory ?: run {
            listener?.onError("PeerConnectionFactory is null")
            return
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("AUDIO_TRACK", audioSource)
        audioTrack?.setEnabled(true)
    }

    private fun createPeerConnection(): PeerConnection? {
        val factory = peerConnectionFactory
        if (factory == null) {
            listener?.onError("PeerConnectionFactory is null (did you call init?)")
            return null
        }

        val iceServers = listOf(
            // STUN (srflx)
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80")
                .createIceServer(),

            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),

            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),

            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:3478")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),

            // TURN TCP 443
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),

            // TURN TLS 443 (necessary for some carriers)
            PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),

            )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // For learning and exploration purpose only
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            // force it through TURN relay (to bypass annoying NAT/firewall)
            iceTransportsType = PeerConnection.IceTransportsType.ALL

            // optional but helped for some cases
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED

            // Let the ICE to keep gathering of candidates while the network is unstable/changing
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

            // (Optional) speed up ICE startup; suitable for call setup
            iceCandidatePoolSize = 4

            // (Optional) if found issues in some particular networks
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("RTC", "ICE CONNECTION = $state")

                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        // FINAL SUCCESS via ICE
                        emitFinalSuccess(via = "ICE:$state")
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        // FINAL FAILED via ICE
                        emitFinalFailure("ICE failed")
                    }

                    PeerConnection.IceConnectionState.CLOSED -> {
                        // CLOSED is not an automatic failure
                        // If it is not final and the session is still active, it will be considered a failure
                        if (sessionActive && !finalEmitted) {
                            emitFinalFailure("ICE closed before connected")
                        }
                    }

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d("RTC", "ICE GATHERING = $state")
//                flushPendingIce()
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (!guardActive()) return
                candidate ?: return

                val sdp = candidate.sdp
                val type =
                    when {
                        sdp.contains(" typ relay ") -> "relay"
                        sdp.contains(" typ srflx ") -> "srflx"
                        sdp.contains(" typ prflx ") -> "prflx"
                        sdp.contains(" typ host ") -> "host"
                        else -> "unknown"
                    }

                iceCount[type] = (iceCount[type] ?: 0) + 1

                Log.d(
                    "RTC",
                    "LOCAL ICE type=$type count=$iceCount mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex} ${
                        sdp.take(80)
                    }"
                )

                onLocalIceCandidate?.invoke(candidate)
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.d("RTC", "PC CONNECTION = $state")

                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        // FINAL SUCCESS via PeerConnection
                        emitFinalSuccess(via = "PC:CONNECTED")
                    }

                    PeerConnection.PeerConnectionState.FAILED -> {
                        // FINAL FAILED via PC
                        emitFinalFailure("PeerConnection failed")
                    }

                    PeerConnection.PeerConnectionState.CLOSED -> {
//                        if (sessionActive && !finalEmitted) emitFinalFailure("PeerConnection closed before connected")
                        Log.w("RTC", "PC CLOSED (not treating as final failure)")
                    }

                    else -> Unit
                }
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {}

            override fun onAddStream(p0: MediaStream?) {}

            override fun onRemoveStream(p0: MediaStream?) {}

            override fun onDataChannel(p0: DataChannel?) {}

            override fun onRenegotiationNeeded() {}
        }

        return factory.createPeerConnection(rtcConfig, observer)
    }

    private fun addLocalAudioTrackToPeerConnection() {
        val pc = peerConnection ?: return
        val streamId = "stream_audio"
        val track = audioTrack ?: run {
            listener?.onError("AudioTrack is null")
            return
        }
        pc.addTrack(track, listOf(streamId))
    }

    private fun resetSessionState() {
        isRemoteSdpSet = false
        pendingIce.clear()
    }

    fun startCallAsCaller(context: Context, roomId: String) {
        sessionActive = true
        ensureCallScope()
        resetSessionState()
        resetFinalOutcome()

        startConnectTimeout() // Failed final indicator if unreachable/can't be connected

        listener?.onState(CallState.Preparing)
        init(context)
        createAudioTrack()

        peerConnection = createPeerConnection() ?: return
        addLocalAudioTrackToPeerConnection()

        onLocalIceCandidate = { candidate ->
            callScope?.launch {
                if (!guardActive()) return@launch
                signaling.addCallerCandidate(roomId, candidate)
            }
        }

        listener?.onState(CallState.CreatingOffer)

        callScope?.launch {
            signaling.resetRoom(roomId)
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    if (!guardActive()) return

                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            if (!guardActive()) return

                            callScope?.launch {
                                signaling.publishOffer(roomId, desc)
                            }
                            listener?.onState(CallState.WaitingAnswer)

                            // listen answer
                            answerListener?.remove()
                            answerListener = signaling.listenAnswer(roomId) { answer ->
                                if (!guardActive()) return@listenAnswer

                                peerConnection?.setRemoteDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        isRemoteSdpSet = true
                                        flushPendingIce()
                                        listener?.onState(CallState.ExchangingIce)
                                    }

                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {
                                        listener?.onError("setRemote answer failed: $p0")
                                    }
                                }, answer)
                            }

                            candListener?.remove()
                            candListener = signaling.listenCalleeCandidates(roomId) { c ->
                                if (!guardActive()) return@listenCalleeCandidates
                                handleRemoteIce(c)
                            }
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }
    }

    fun joinCallAsCallee(context: Context, roomId: String) {
        sessionActive = true
        ensureCallScope()
        resetSessionState()
        resetFinalOutcome()

        startConnectTimeout()  // Failed final indicator if unreachable/can't be connected

        listener?.onState(CallState.Preparing)
        init(context)
        createAudioTrack()

        peerConnection = createPeerConnection() ?: return
        addLocalAudioTrackToPeerConnection()

        onLocalIceCandidate = { c ->
            callScope?.launch { signaling.addCalleeCandidate(roomId, c) }
        }

        listener?.onState(CallState.WaitingOffer) // waiting offer actually

        // listen offer
        offerListener?.remove()
        offerListener = signaling.listenOffer(roomId) { offer ->
            if (!guardActive()) return@listenOffer

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    isRemoteSdpSet = true

                    candListener?.remove()
                    candListener = signaling.listenCallerCandidates(roomId) { c ->
                        if (!guardActive()) return@listenCallerCandidates
                        handleRemoteIce(c)
                    }

                    listener?.onState(CallState.CreatingAnswer)

                    callScope?.launch {
                        delay(300)
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                if (!guardActive()) return

                                peerConnection?.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        if (!guardActive()) return

                                        callScope?.launch {
                                            signaling.publishAnswer(
                                                roomId,
                                                answer
                                            )
                                        }
                                        listener?.onState(CallState.ExchangingIce)

                                        // listen caller candidates
                                        candListener?.remove()
                                        candListener =
                                            signaling.listenCallerCandidates(roomId) { c ->
                                                if (!guardActive()) return@listenCallerCandidates
                                                handleRemoteIce(c)
                                            }
                                    }

                                    override fun onSetFailure(p0: String?) {
                                        listener?.onError("setLocal answer failed: $p0")
                                    }

                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, answer)
                            }

                            override fun onCreateFailure(p0: String?) {
                                listener?.onError("createAnswer failed: $p0")
                            }

                            override fun onSetSuccess() {}
                            override fun onSetFailure(p0: String?) {}
                        }, MediaConstraints())
                    }
                }

                override fun onSetFailure(p0: String?) {
                    listener?.onError("setRemote offer failed: $p0")
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        }
    }

    private fun emitFinalSuccess(via: String) {
        if (finalEmitted) return
        finalEmitted = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        listener?.onState(CallState.ConnectedFinal(via))
    }

    private fun emitFinalFailure(reason: String) {
        if (finalEmitted) return
        finalEmitted = true
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        listener?.onState(CallState.FailedFinal(reason))
    }

    private fun startConnectTimeout(seconds: Long = 30) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = callScope?.launch {
            delay(seconds * 1000)
            if (!finalEmitted) {
                emitFinalFailure("Timeout: no CONNECTED within ${seconds}s")
            }
        }
    }

    private fun resetFinalOutcome() {
        finalEmitted = false
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun releaseAudioResources() {
        unregisterAudioMonitoring()

        val am = audioManager ?: run {
            audioFocusChangeListener = null
            clearAudioState()
            return
        }

        runCatching {
            // Abandon focus (legacy)
            @Suppress("DEPRECATION")
            audioFocusChangeListener?.let { am.abandonAudioFocus(it) }
        }

        runCatching {
            // Restore previous audio states
            prevAudioMode?.let { am.mode = it }
            prevSpeakerOn?.let { am.isSpeakerphoneOn = it }
            prevBluetoothScoOn?.let { am.isBluetoothScoOn = it }
            am.stopBluetoothSco()
        }

        clearAudioState()
    }

    fun setMuted(muted: Boolean) {
        runCatching {
            audioTrack?.setEnabled(!muted)
        }.onFailure {
            Log.w("RTC", "setMuted failed", it)
        }
    }

    fun setSpeakerOn(on: Boolean) {
        desiredRoute = if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE
        applyAudioRoute()
    }

    private fun registerAudioDeviceMonitoring(context: Context) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        if (audioDeviceCallback != null) return

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo?>?) {
                // if device entered, please reroute (eg: BT just connected)
                applyAudioRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo?>?) {
                // if BT/wired removed/released, give fallback
                applyAudioRoute()
            }
        }

        audioDeviceCallback = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun registerScoReceiver(context: Context) {
        if (scoReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )

                lastScoState = state

                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        _isBluetoothActive.value = true
                        // Optional: ensure speaker off when BT connected
                        audioManager?.isSpeakerphoneOn = false
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        _isBluetoothActive.value = false
                        // If: BT device still exist, try to start again at once (auto-recover)
                        if (hasBluetoothScoDevice()) applyAudioRoute()
                    }
                }
            }
        }

        scoReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }

    private fun unregisterAudioMonitoring() {
        val context = appContext
        val am = audioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { audioDeviceCallback?.let { am?.unregisterAudioDeviceCallback(it) } }
        }
        audioDeviceCallback = null

        runCatching { scoReceiver?.let { context?.unregisterReceiver(it) } }
        scoReceiver = null
    }

    private fun hasBluetoothScoDevice(): Boolean {
        val am = audioManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return am.isBluetoothScoAvailableOffCall

        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun hasWiredHeadset(): Boolean {
        val am = audioManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun applyAudioRoute() {
        val am = audioManager ?: return

        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // 1. Wired headset always wins
            if (hasWiredHeadset()) {
                stopScoIfAny(am)
                am.isSpeakerphoneOn = false
                _isBluetoothActive.value = false
                return
            }

            // 2. Bluetooth auto-route (NO TOGGLE)
            if (hasBluetoothScoDevice()) {
                am.isSpeakerphoneOn = false

                // If not connected, try to start
                if (!am.isBluetoothScoOn && lastScoState != AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    am.startBluetoothSco()
                }

                // The "active" indicator is more accurate based on the connected SCO (via receiver),
                // but provided a "best effort" fallback:
                _isBluetoothActive.value = am.isBluetoothScoOn || lastScoState == AudioManager.SCO_AUDIO_STATE_CONNECTED
                return
            }


            // 3. Speaker only if user explicitly enabled it
            if (desiredRoute == AudioRoute.SPEAKER) {
                stopScoIfAny(am)
                am.isSpeakerphoneOn = true
                _isBluetoothActive.value = false
                return
            }

            // 4. Default fallback: earpiece
            stopScoIfAny(am)
            am.isSpeakerphoneOn = false
            _isBluetoothActive.value = false
        }.onFailure {
            Log.w("RTC", "applyAudioRoute failed", it)
        }
    }

    private fun stopScoIfAny(am: AudioManager) {
        runCatching {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        }
    }

    private fun clearAudioState() {
        audioFocusChangeListener = null
        prevAudioMode = null
        prevSpeakerOn = null
        prevBluetoothScoOn = null
        audioManager = null
        appContext = null
    }
}