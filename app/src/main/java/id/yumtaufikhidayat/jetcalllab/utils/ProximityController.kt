package id.yumtaufikhidayat.jetcalllab.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns screen off when device is near user's face (proximity) using a WakeLock.
 *
 * Core idea:
 * - Register proximity sensor
 * - When NEAR -> acquire PROXIMITY_SCREEN_OFF_WAKE_LOCK
 * - When FAR  -> release it
 *
 * Enable it ONLY for in-call earpiece mode.
 */

class ProximityController(
    context: Context,
    private val logTag: String = "Proximity",
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    val isSupported: Boolean = proximitySensor != null

    private val _isNear = MutableStateFlow(false)
    val isNear: StateFlow<Boolean> = _isNear.asStateFlow()

    private val _isWakeLockHeld = MutableStateFlow(false)
    val isWakeLockHeld: StateFlow<Boolean> = _isWakeLockHeld.asStateFlow()

    // NOTE: PROXIMITY_SCREEN_OFF_WAKE_LOCK is deprecated, but still used by many calling apps.
    // It works only on devices that support proximity wake locks.
    private val proximityWakeLock: PowerManager.WakeLock? =
        if (isSupported) {
            runCatching {
                @Suppress("DEPRECATION")
                powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "${appContext.packageName}:proximity"
                ).apply { setReferenceCounted(false) }
            }.getOrNull()
        } else null

    private var started = false

    // Input signals (set from Service)
    private var inCall: Boolean = false
    private var speakerOn: Boolean = false
    private var bluetoothActive: Boolean = false
    private var wiredHeadset: Boolean = false

    /**
     * Start listening sensor events. Safe to call multiple times.
     */
    fun start() {
        if (started) return
        started = true

        if (!isSupported) {
            Log.d(logTag, "Proximity not supported on this device (sensor=null)")
            return
        }

        runCatching {
            sensorManager.registerListener(
                this,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }.onFailure {
            Log.w(logTag, "Failed to register proximity listener", it)
        }

        evaluate()
    }

    /**
     * Stop listening and release wakelock.
     */
    fun stop() {
        if (!started) return
        started = false

        runCatching { sensorManager.unregisterListener(this) }
        releaseWakeLockIfHeld()
        _isNear.value = false
    }

    /**
     * Update inputs from call state.
     */
    fun updateCallConditions(
        inCall: Boolean,
        speakerOn: Boolean,
        bluetoothActive: Boolean,
        wiredHeadset: Boolean,
    ) {
        this.inCall = inCall
        this.speakerOn = speakerOn
        this.bluetoothActive = bluetoothActive
        this.wiredHeadset = wiredHeadset
        evaluate()
    }

    /**
     * Decide whether we should hold the proximity screen-off wakelock.
     */
    private fun evaluate() {
        if (!isSupported) return

        val shouldEnable =
            inCall &&
                    !speakerOn &&
                    !bluetoothActive &&
                    !wiredHeadset

        if (!shouldEnable) {
            // If route is not earpiece, never hold wakelock.
            releaseWakeLockIfHeld()
            return
        }

        // Only lock screen when user is actually "near".
        if (_isNear.value) acquireWakeLockIfNeeded() else releaseWakeLockIfHeld()
    }

    private fun acquireWakeLockIfNeeded() {
        val wl = proximityWakeLock ?: return
        if (_isWakeLockHeld.value) return

        runCatching {
            wl.acquire()
            _isWakeLockHeld.value = true
            Log.d(logTag, "Proximity wakelock acquired")
        }.onFailure {
            Log.w(logTag, "Failed to acquire proximity wakelock", it)
        }
    }

    private fun releaseWakeLockIfHeld() {
        val wl = proximityWakeLock ?: return
        if (!_isWakeLockHeld.value) return

        runCatching {
            wl.release()
            _isWakeLockHeld.value = false
            Log.d(logTag, "Proximity wakelock released")
        }.onFailure {
            Log.w(logTag, "Failed to release proximity wakelock", it)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isSupported) return
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return

        val distance = event.values.firstOrNull() ?: return
        val maxRange = proximitySensor?.maximumRange ?: 0f

        // Near if distance < maxRange (common pattern)
        val near = distance < maxRange
        if (_isNear.value != near) {
            _isNear.value = near
            Log.d(logTag, "Proximity near=$near distance=$distance max=$maxRange")
            evaluate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}