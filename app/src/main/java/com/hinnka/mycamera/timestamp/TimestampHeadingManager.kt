package com.hinnka.mycamera.timestamp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI

/** Lightweight heading source used only while Timestamp mode is active. */
class TimestampHeadingManager(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var started = false

    fun start() {
        if (started || rotationVector == null) return
        started = sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (started) sensorManager.unregisterListener(this)
        started = false
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        _heading.value = normalizeHeading((orientation[0] * 180f / PI.toFloat()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
