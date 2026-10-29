/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Track.
 *
 * Pingolin Track is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Track is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.track.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Motion detection from the device's own sensors, for BOTH flavours.
 *
 * Requests no permission. TYPE_SIGNIFICANT_MOTION and TYPE_ACCELEROMETER are
 * not behind `android.permission.ACTIVITY_RECOGNITION` — that guards the Play
 * services API and the step counter — so nothing here prompts the user.
 *
 * WHY THERE IS ONLY ONE OF THESE
 * Both flavours use this rather than Play services Activity Recognition, which
 * needs ACTIVITY_RECOGNITION. Declaring that makes Play class the app as using a
 * sensitive permission and ask it to declare health features — on top of the
 * ACCESS_BACKGROUND_LOCATION declaration a location-sharing app already carries
 * — to power nothing more than the choice of a GPS poll interval. This uses no
 * Google artifacts, which is what lets it live in `src/main` at all (see
 * `MainSourceSetIsGoogleFreeTest`).
 *
 * `LocationTrackingService` injects it, calls `start()`/`stop()` and collects
 * [motionState].
 *
 * WHY THIS IS SMALLER THAN IT LOOKS
 * [MotionState] is STILL-vs-MOVING and nothing more, so no activity
 * CLASSIFICATION is needed here — only the two edges:
 *
 *   STILL -> MOVING   [Sensor.TYPE_SIGNIFICANT_MOTION]. A hardware trigger
 *                     sensor: the SoC's sensor hub watches the accelerometer
 *                     with the application processor asleep and wakes us once,
 *                     for near-zero power. This is the same primitive Android's
 *                     own "device idle" machinery uses.
 *
 *   MOVING -> STILL   [Sensor.TYPE_STATIONARY_DETECT] where the device has
 *                     one: a second hub trigger, wake-up and one-shot, that
 *                     fires once the device has been stationary for ~5 s. Its
 *                     5 s is far shorter than our dwell, so a trigger only OPENS
 *                     a confirmation window — significant motion is armed, and
 *                     STILL is committed if nothing moves for the rest of
 *                     [STILL_DWELL_MS]. No accelerometer runs at all.
 *
 *                     Fallback: an accelerometer variance window with a dwell
 *                     ([StillnessWindow]). Significant motion CANNOT express
 *                     this — it only ever fires on the way into motion — so
 *                     stopping has to be observed directly. It is registered
 *                     ONLY while MOVING, so a device sitting on a table runs no
 *                     accelerometer at all and costs what the gms build costs.
 *                     It is BATCHED ([MAX_REPORT_LATENCY_US]) on its own thread:
 *                     a non-wake-up sensor's samples are not delivered while
 *                     the application processor sleeps, and a dwell starved of
 *                     samples could leave the device in MOVING, polling GPS
 *                     every 20 s, for as long as that lasted. The hub keeps the
 *                     timestamps, so a late batch still measures the dwell
 *                     correctly. `LocationTrackingService` also has a
 *                     GPS-evidence way out of a MOVING that never ends
 *                     (shouldHoldStillCadenceWhileMoving).
 *
 * ONE-SHOT, SO RE-ARM. TYPE_SIGNIFICANT_MOTION automatically unregisters itself
 * when it fires ([SensorManager.requestTriggerSensor] is one request, one
 * trigger). Every path that leaves MOVING re-requests it; forgetting to would
 * silently pin the device at STILL forever, with GPS at POLL_STILL_MS.
 *
 * ENTER-ONLY SEMANTICS. A state is only ever derived from a signal that
 * something BEGAN: deriving one from an exit (EXIT STILL -> STILL) would pin GPS
 * polling at POLL_STILL_MS = 5 min even while the user was moving. Each signal
 * below is inherently an ENTER — a trigger means "motion has begun", a satisfied
 * dwell means "stillness has begun" — and nothing derives a state from the
 * ABSENCE of a signal. Do not add a timeout that sets STILL because the trigger
 * has not fired; that would pin the state in the same way.
 *
 * NO SENSORS, NO OPINION. A device without both sensors leaves the state
 * [MotionState.UNKNOWN] permanently, which `LocationTrackingService` already
 * handles: it polls at POLL_UNKNOWN_MS and
 * `LocationTrackingService.maybeInferStill` promotes UNKNOWN to STILL from GPS
 * evidence alone. Requiring BOTH is deliberate — with significant motion but no
 * accelerometer we could enter MOVING and never leave, pinning the poll at
 * POLL_MOVING_MS (20 s), which is worse for the battery than never having an
 * opinion.
 */
@Singleton
class MotionDetectionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "MotionDetection"

        /**
         * Accelerometer sampling period while MOVING. 200 ms (5 Hz) is far below
         * the game/UI rates and is plenty to see whether a phone is being carried
         * — we are measuring the PRESENCE of motion, not its shape.
         */
        internal const val SAMPLE_PERIOD_US = 200_000

        /** Sliding window the variance is computed over. */
        internal const val WINDOW_MS = 5_000L

        /**
         * Variance of the accelerometer magnitude, in (m/s^2)^2, below which the
         * device counts as quiet. A phone at rest reads a near-constant ~9.81
         * (gravity) with only sensor noise on top, typically well under 0.05; a
         * phone in a pocket while walking swings by several m/s^2, which is two
         * orders of magnitude more variance. The threshold sits nearer the quiet
         * end on purpose: calling MOVING when still costs battery, calling STILL
         * when moving costs a stale marker, and the second is the one users
         * notice.
         */
        internal const val STILL_VARIANCE_THRESHOLD = 0.35

        /**
         * How long the variance must stay quiet before we declare STILL. Long
         * enough that a pause at a traffic light or a phone set down for a moment
         * mid-walk does not flip the polling profile; short enough that arriving
         * home backs GPS off within a minute.
         */
        internal const val STILL_DWELL_MS = 45_000L

        /** Below this many samples the window has too little to say. */
        internal const val MIN_SAMPLES = 8

        /**
         * Batching latency for the fallback accelerometer: the hub may hold samples
         * this long before delivering them. Long enough to let the application
         * processor sleep between batches, short enough that the dwell still ends
         * within seconds of its 45 s.
         */
        internal const val MAX_REPORT_LATENCY_US = 10_000_000

        /**
         * How long a device must already be still before TYPE_STATIONARY_DETECT
         * fires, per the Android sensor definition. The confirmation window after it
         * is the rest of [STILL_DWELL_MS].
         */
        internal const val STATIONARY_DETECT_STILL_MS = 5_000L

        /** Euclidean magnitude of an accelerometer sample, in m/s^2 (gravity included). */
        internal fun magnitudeOf(x: Float, y: Float, z: Float): Double =
            sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z))
    }

    private val _motionState = MutableStateFlow(MotionState.UNKNOWN)
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private val significantMotion: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }
    private val accelerometer: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    /**
     * The wake-up, one-shot stationary trigger, where the hardware has one and it
     * behaves as a trigger sensor. Null means the accelerometer dwell is used.
     */
    private val stationaryDetect: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)
            ?.takeIf { it.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT }
    }

    private val stillness = StillnessWindow()

    @Volatile private var isStarted = false
    /** Guards the two registration pairs; every register/unregister goes through it. */
    private val sensorLock = Any()
    private var accelerometerRegistered = false
    private var stationaryArmed = false

    /**
     * Sensor callbacks and the STILL confirmation run here, off the main looper.
     * Created on first use, so a device with no sensors never starts a thread.
     */
    private var sensorThread: HandlerThread? = null
    private val sensorHandler: Handler by lazy {
        val thread = HandlerThread("MotionDetection").apply { start() }
        sensorThread = thread
        Handler(thread.looper)
    }

    /** Commits STILL at the end of a stationary-trigger confirmation window. */
    private val commitStill = Runnable {
        synchronized(sensorLock) {
            if (!isStarted || _motionState.value != MotionState.MOVING) return@Runnable
        }
        Log.i(TAG, "Stationary for the whole dwell — MOVING -> STILL")
        updateState(MotionState.STILL)
        watchForMotion()
    }

    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // The sensor has already unregistered itself at this point — see the
            // class comment. watchForStillness() is what re-arms it, on the way
            // back to STILL. A pending stationary confirmation is void: something
            // moved.
            Log.d(TAG, "Significant motion triggered")
            sensorHandler.removeCallbacks(commitStill)
            updateState(MotionState.MOVING)
            watchForStillness()
        }
    }

    private val stationaryTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // One-shot: already disarmed. Open the confirmation window — arm the
            // motion edge so anything that moves cancels it, and commit STILL once
            // the rest of the dwell has passed without that.
            synchronized(sensorLock) {
                stationaryArmed = false
                if (!isStarted || _motionState.value != MotionState.MOVING) return
                val sm = significantMotion
                if (sm != null) runCatching { sensorManager?.requestTriggerSensor(motionTrigger, sm) }
            }
            Log.d(TAG, "Stationary detected — confirming over the rest of the dwell")
            sensorHandler.removeCallbacks(commitStill)
            sensorHandler.postDelayed(commitStill, STILL_DWELL_MS - STATIONARY_DETECT_STILL_MS)
        }
    }

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val magnitude = magnitudeOf(event.values[0], event.values[1], event.values[2])
            // event.timestamp is elapsed-realtime NANOSECONDS from the sensor
            // hub, not wall clock — immune to clock changes and to how long the
            // batch sat in the queue.
            if (!stillness.onSample(magnitude, event.timestamp / 1_000_000L)) return
            Log.i(TAG, "Stillness dwell satisfied — MOVING -> STILL")
            updateState(MotionState.STILL)
            watchForMotion()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (isStarted) return
        if (significantMotion == null || accelerometer == null) {
            Log.w(
                TAG,
                "No motion detection: significantMotion=${significantMotion != null}, " +
                    "accelerometer=${accelerometer != null} — staying UNKNOWN"
            )
            return
        }
        isStarted = true

        // Restore last known state, exactly as the gms copy does, so the polling
        // profile is sane before the first sensor event lands.
        val lastState = sessionManager.getLastMotionState()
        if (lastState != null) {
            _motionState.value = lastState
        }

        // Arm the edge we are NOT currently on. Restoring MOVING and then arming
        // significant motion would wait forever for a trigger that only fires on
        // the way INTO motion; a device that has since been put down would never
        // reach STILL.
        if (_motionState.value == MotionState.MOVING) watchForStillness() else watchForMotion()
        Log.i(TAG, "Sensor motion detection started (state=${_motionState.value})")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        synchronized(sensorLock) {
            significantMotion?.let { runCatching { sensorManager?.cancelTriggerSensor(motionTrigger, it) } }
            if (stationaryArmed) {
                stationaryDetect?.let { runCatching { sensorManager?.cancelTriggerSensor(stationaryTrigger, it) } }
                stationaryArmed = false
            }
            if (accelerometerRegistered) {
                runCatching { sensorManager?.unregisterListener(accelerometerListener) }
                accelerometerRegistered = false
            }
        }
        if (sensorThread != null) sensorHandler.removeCallbacks(commitStill)
        stillness.reset()
        Log.i(TAG, "Sensor motion detection stopped")
    }

    fun updateState(newState: MotionState) {
        val oldState = _motionState.value
        _motionState.value = newState
        sessionManager.setLastMotionState(newState)
        if (oldState != newState) {
            Log.i(TAG, "Motion state changed: $oldState -> $newState")
        }
    }

    /** Arm the cheap edge: wait for the hardware to tell us motion began. */
    private fun watchForMotion() {
        synchronized(sensorLock) {
            if (!isStarted) return
            if (accelerometerRegistered) {
                runCatching { sensorManager?.unregisterListener(accelerometerListener) }
                accelerometerRegistered = false
            }
            if (stationaryArmed) {
                stationaryDetect?.let { runCatching { sensorManager?.cancelTriggerSensor(stationaryTrigger, it) } }
                stationaryArmed = false
            }
            stillness.reset()
            val sensor = significantMotion ?: return
            // Cancel first: a stationary-trigger confirmation window may already have
            // armed it, and requesting a trigger that is already requested reports
            // failure on some devices.
            runCatching { sensorManager?.cancelTriggerSensor(motionTrigger, sensor) }
            val armed = runCatching { sensorManager?.requestTriggerSensor(motionTrigger, sensor) }
                .getOrNull() ?: false
            if (!armed) Log.w(TAG, "Could not arm the significant-motion sensor")
        }
    }

    /**
     * Arm the way back to STILL — only ever while MOVING. The stationary trigger
     * where the hardware has one, the (batched) accelerometer dwell otherwise, or
     * when arming the trigger fails.
     */
    private fun watchForStillness() {
        synchronized(sensorLock) {
            if (!isStarted) return
            if (accelerometerRegistered || stationaryArmed) return
            val stationary = stationaryDetect
            if (stationary != null) {
                val armed = runCatching { sensorManager?.requestTriggerSensor(stationaryTrigger, stationary) }
                    .getOrNull() ?: false
                if (armed) {
                    stationaryArmed = true
                    return
                }
                Log.w(TAG, "Could not arm the stationary trigger; falling back to the accelerometer dwell")
            }
            val sensor = accelerometer ?: return
            stillness.reset()
            val registered = runCatching {
                sensorManager?.registerListener(
                    accelerometerListener, sensor, SAMPLE_PERIOD_US, MAX_REPORT_LATENCY_US, sensorHandler,
                )
            }.getOrNull() ?: false
            accelerometerRegistered = registered
            if (!registered) {
                // Without the accelerometer we can never observe the way back to
                // STILL, so re-arm the trigger instead of sitting in MOVING with
                // GPS pinned at POLL_MOVING_MS.
                Log.w(TAG, "Could not register the accelerometer; re-arming significant motion")
                val sm = significantMotion ?: return
                runCatching { sensorManager?.requestTriggerSensor(motionTrigger, sm) }
            }
        }
    }
}

/**
 * "Has this device been quiet long enough to call it STILL?", as a pure state
 * machine so it can be tested without a phone (`MotionDetectionTest`).
 * Feed it accelerometer magnitudes; it answers true once the variance has stayed
 * under [varianceThreshold] continuously for [dwellMs].
 *
 * Top-level rather than nested in [MotionDetectionManager]'s companion purely so
 * the test can name it as `StillnessWindow(...)`; a classifier declared inside a
 * companion object is only reachable as `Outer.Companion.Inner`.
 *
 * The dwell is CONTINUOUS: a single loud sample resets the clock, so "quiet,
 * loud, quiet" never adds up to a dwell. That is the property that keeps a phone
 * put down at a red light from backing GPS off for the rest of the drive.
 */
internal class StillnessWindow(
    private val windowMs: Long = MotionDetectionManager.WINDOW_MS,
    private val varianceThreshold: Double = MotionDetectionManager.STILL_VARIANCE_THRESHOLD,
    private val dwellMs: Long = MotionDetectionManager.STILL_DWELL_MS,
    private val minSamples: Int = MotionDetectionManager.MIN_SAMPLES,
) {
    private val timestamps = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Double>()
    private var quietSinceMs: Long? = null

    /** @return true the first time the dwell is satisfied, and while it stays satisfied. */
    fun onSample(magnitude: Double, atMs: Long): Boolean {
        // A gap in the sample TIMESTAMPS longer than the window means samples were
        // lost (the hub dropped them, the sensor was suspended), not merely delivered
        // late — batching delays delivery but keeps the timestamps continuous. Nothing
        // was observed during the gap, so it cannot count towards the dwell: start
        // over, or eight quiet samples after a long gap would satisfy a dwell whose
        // start predated the gap.
        val lastAt = timestamps.lastOrNull()
        if (lastAt != null && atMs - lastAt > windowMs) reset()
        timestamps.addLast(atMs)
        magnitudes.addLast(magnitude)
        while (timestamps.isNotEmpty() && atMs - timestamps.first() > windowMs) {
            timestamps.removeFirst()
            magnitudes.removeFirst()
        }
        if (magnitudes.size < minSamples) return false

        if (variance(magnitudes) > varianceThreshold) {
            quietSinceMs = null
            return false
        }
        val since = quietSinceMs ?: atMs.also { quietSinceMs = it }
        return atMs - since >= dwellMs
    }

    /** Forget everything — used when the device re-enters MOVING. */
    fun reset() {
        timestamps.clear()
        magnitudes.clear()
        quietSinceMs = null
    }

    private fun variance(values: Collection<Double>): Double {
        val mean = values.sum() / values.size
        return values.sumOf { val d = it - mean; d * d } / values.size
    }
}
