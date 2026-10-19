package com.hinnka.mycamera.livephoto

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.hinnka.mycamera.utils.PLog
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Immutable recorder result. Sensor timestamps and camera timestamps share CLOCK_BOOTTIME. */
data class MotionPhotoCapture(
    val file: File,
    val primaryTimestampUs: Long,
    val videoStartTimestampUs: Long,
    val orientationTrack: MotionPhotoOrientationTrack? = null,
)

data class MotionPhotoOrientationSample(
    val timestampUs: Long,
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val confidence: Float,
)

/** Conservative virtual orientation source. It is only a rotation prior for visual tracking. */
data class MotionPhotoOrientationTrack(
    val samples: List<MotionPhotoOrientationSample>,
    val frontFacing: Boolean,
) {
    data class RotationPrior(val radians: Float, val confidence: Float)

    fun relativeRoll(previousTimestampUs: Long, currentTimestampUs: Long): RotationPrior? {
        val previous = nearest(previousTimestampUs) ?: return null
        val current = nearest(currentTimestampUs) ?: return null
        if (abs(previous.timestampUs - previousTimestampUs) > MAX_SAMPLE_DISTANCE_US ||
            abs(current.timestampUs - currentTimestampUs) > MAX_SAMPLE_DISTANCE_US
        ) return null
        val inversePrevious = floatArrayOf(previous.w, -previous.x, -previous.y, -previous.z)
        val delta = multiply(inversePrevious, floatArrayOf(current.w, current.x, current.y, current.z))
        // Twist about device Z corresponds to roll around the camera optical axis. Pitch/yaw are
        // intentionally ignored because their image displacement depends on scene depth and FOV.
        var roll = 2f * atan2(delta[3], delta[0])
        while (roll > Math.PI) roll -= (Math.PI * 2).toFloat()
        while (roll < -Math.PI) roll += (Math.PI * 2).toFloat()
        if (frontFacing) roll = -roll
        val confidence = minOf(previous.confidence, current.confidence)
        if (confidence < .45f || abs(roll) > .14f) return null
        return RotationPrior(roll, confidence)
    }

    private fun nearest(timestampUs: Long): MotionPhotoOrientationSample? {
        if (samples.isEmpty()) return null
        var low = 0
        var high = samples.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (samples[middle].timestampUs < timestampUs) low = middle + 1 else high = middle
        }
        val after = samples[low]
        val before = samples.getOrNull(low - 1)
        return if (before != null && abs(before.timestampUs - timestampUs) <
            abs(after.timestampUs - timestampUs)) before else after
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    private companion object {
        const val MAX_SAMPLE_DISTANCE_US = 80_000L
    }
}

/** Records geomagnetic rotation or an accelerometer/magnetometer fallback off the UI thread. */
internal class MotionPhotoOrientationRecorder(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val geomagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val lock = Any()
    private val samples = ArrayDeque<MotionPhotoOrientationSample>(MAX_SAMPLES)
    private val acceleration = FloatArray(3)
    private val magnetic = FloatArray(3)
    private var accelerationTimestampUs = 0L
    private var magneticTimestampUs = 0L
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var thread: HandlerThread? = null

    fun start() {
        if (thread != null) return
        synchronized(lock) { samples.clear() }
        val worker = HandlerThread("Rcam-MotionOrientation").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        val registered = if (geomagnetic != null) {
            sensorManager.registerListener(this, geomagnetic, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            accelerometer != null && magnetometer != null &&
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler) &&
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        if (!registered) {
            PLog.w(TAG, "No usable virtual orientation sensors; Motion Photo remains visual-only")
            stop()
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    fun snapshot(startTimestampUs: Long, endTimestampUs: Long, frontFacing: Boolean): MotionPhotoOrientationTrack? {
        val selected = synchronized(lock) {
            samples.filter { it.timestampUs in (startTimestampUs - 100_000L)..(endTimestampUs + 100_000L) }
        }
        return selected.takeIf { it.size >= 4 }?.let { MotionPhotoOrientationTrack(it, frontFacing) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, value: Int) {
        if (sensor?.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ||
            sensor?.type == Sensor.TYPE_MAGNETIC_FIELD
        ) accuracy = value
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
            val quaternion = FloatArray(4)
            SensorManager.getQuaternionFromVector(quaternion, event.values)
            append(event.timestamp / 1_000L, quaternion, accuracyConfidence(event.accuracy))
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(acceleration, endIndex = 3)
                accelerationTimestampUs = event.timestamp / 1_000L
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magnetic, endIndex = 3)
                magneticTimestampUs = event.timestamp / 1_000L
            }
        }
        if (abs(accelerationTimestampUs - magneticTimestampUs) > 80_000L) return
        val rotation = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotation, null, acceleration, magnetic)) return
        val gravity = magnitude(acceleration)
        val field = magnitude(magnetic)
        val physicalConfidence = (1f - abs(gravity - SensorManager.GRAVITY_EARTH) / 3.2f)
            .coerceIn(0f, 1f) * if (field in 20f..100f) 1f else .25f
        append(maxOf(accelerationTimestampUs, magneticTimestampUs), matrixQuaternion(rotation),
            physicalConfidence * accuracyConfidence(accuracy))
    }

    private fun append(timestampUs: Long, quaternion: FloatArray, confidence: Float) {
        val norm = sqrt(quaternion.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < .5f || !norm.isFinite()) return
        val sample = MotionPhotoOrientationSample(timestampUs, quaternion[0] / norm,
            quaternion[1] / norm, quaternion[2] / norm, quaternion[3] / norm,
            confidence.coerceIn(0f, 1f))
        synchronized(lock) {
            if (samples.lastOrNull()?.timestampUs == timestampUs) return
            samples.addLast(sample)
            while (samples.size > MAX_SAMPLES) samples.removeFirst()
        }
    }

    private fun matrixQuaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[4] + m[8]
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            return floatArrayOf(.25f * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s,
                (m[3] - m[1]) / s)
        }
        val index = if (m[0] > m[4] && m[0] > m[8]) 0 else if (m[4] > m[8]) 1 else 2
        return when (index) {
            0 -> {
                val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
                floatArrayOf((m[7] - m[5]) / s, .25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            }
            1 -> {
                val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
                floatArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, .25f * s, (m[5] + m[7]) / s)
            }
            else -> {
                val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
                floatArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, .25f * s)
            }
        }
    }

    private fun magnitude(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun accuracyConfidence(value: Int) = when (value) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1f
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> .72f
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> .42f
        else -> .2f
    }

    private companion object {
        const val TAG = "MotionOrientation"
        const val MAX_SAMPLES = 512
    }
}
